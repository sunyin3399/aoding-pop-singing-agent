import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as VueRouter from 'vue-router'

function compileComponent() {
  const filename = new URL('./HomeView.vue', import.meta.url)
  const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename: filename.pathname })
  if (errors.length) throw errors[0]

  let code = compileScript(descriptor, { id: 'home-view-test', inlineTemplate: true }).content
  code = code
    .replace(/import \{([^}]+)\} from ['"]vue['"]/g, (_, imports) => {
      const bindings = imports
        .split(',')
        .map((part) => part.trim().replace(/\s+as\s+/, ': '))
        .join(', ')
      return `const { ${bindings} } = globalThis.__homeTestVue`
    })
    .replace(/import \{([^}]+)\} from ['"]vue-router['"]/g, (_, imports) => {
      const bindings = imports.split(',').map((part) => part.trim()).join(', ')
      return `const { ${bindings} } = globalThis.__homeTestRouter`
    })
    .replace(/import \{([^}]+)\} from ['"]@element-plus\/icons-vue['"]/g, (_, imports) => {
      const bindings = imports.split(',').map((part) => part.trim()).join(', ')
      return `const { ${bindings} } = globalThis.__homeTestIcons`
    })
    .replace("import { useIdentity } from '../composables/useIdentity'", 'const useIdentity = globalThis.__homeTestIdentity')
    .replace('export default', 'globalThis.__homeTestPage =')
  return code
}

function hostNode(type, text = '') {
  return { type, text, children: [], props: {}, parent: null }
}

const renderer = Vue.createRenderer({
  patchProp(node, key, _previous, value) { node.props[key] = value },
  insert(node, parent, anchor) {
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node)
    else parent.children.splice(index, 0, node)
  },
  remove(node) {
    const index = node.parent?.children.indexOf(node) ?? -1
    if (index >= 0) node.parent.children.splice(index, 1)
  },
  createElement: (type) => hostNode(type),
  createText: (text) => hostNode('#text', text),
  createComment: (text) => hostNode('#comment', text),
  setText(node, text) { node.text = text },
  setElementText(node, text) { node.text = text; node.children = [] },
  parentNode: (node) => node.parent,
  nextSibling: (node) => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
  querySelector: () => null,
  setScopeId() {},
  cloneNode: (node) => ({ ...node, children: [...node.children], props: { ...node.props } }),
  insertStaticContent(content, parent) {
    const node = hostNode('#static', content)
    node.parent = parent
    parent.children.push(node)
    return [node, node]
  },
})

function findAll(node, predicate, matches = []) {
  if (predicate(node)) matches.push(node)
  for (const child of node.children || []) findAll(child, predicate, matches)
  return matches
}

const icon = { render: () => Vue.h('svg') }
globalThis.__homeTestVue = Vue
globalThis.__homeTestRouter = VueRouter
globalThis.__homeTestIcons = {
  ArrowRight: icon,
  Check: icon,
  ChatDotRound: icon,
  DataLine: icon,
  DocumentChecked: icon,
  Lock: icon,
  Microphone: icon,
  Promotion: icon,
  Reading: icon,
}
globalThis.__homeTestIdentity = () => ({ normalizedId: Vue.ref('test-user') })

try {
  await import(`data:text/javascript,${encodeURIComponent(compileComponent())}`)
  const router = VueRouter.createRouter({
    history: VueRouter.createMemoryHistory(),
    routes: [
      { path: '/', component: { render: () => null } },
      { path: '/coach', component: { render: () => null } },
      { path: '/assistant', component: { render: () => null } },
      { path: '/spectrum-lab', component: { render: () => null } },
      { path: '/training-plan', component: { render: () => null } },
      { path: '/admin/knowledge-review', component: { render: () => null } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const root = hostNode('root')
  const app = renderer.createApp(globalThis.__homeTestPage)
  app.use(router)
  app.mount(root)

  const links = findAll(root, (node) => node.type === 'a')
  assert.deepEqual(links.map((node) => node.props.href), ['/coach', '/assistant'])
  const coachLink = links.find((node) => node.props.href === '/coach')
  const agentLink = links.find((node) => node.props.href === '/assistant')
  const trainingPlanLink = links.find((node) => node.props.href === '/training-plan')
  assert.ok(coachLink, 'the vocal coach must render as a native-focusable link')
  assert.ok(agentLink, 'the pop vocal agent must render as a native-focusable link')
  const entryTitles = findAll(root, (node) => node.type === 'strong').map((node) => node.text)
  assert.ok(entryTitles.includes('流行演唱 Agent'))
  assert.equal(trainingPlanLink, undefined, 'the training plan route must remain hidden from the home navigation')
  const previewPrompts = findAll(root, (node) => node.type === 'span').map((node) => node.text)
  assert.ok(previewPrompts.includes('帮我制定一份适合初学者的练唱计划'))
  assert.ok(previewPrompts.includes('推荐几首适合初学者的歌曲'))
  assert.ok(previewPrompts.includes('唱歌大白嗓怎么办'))

  await coachLink.props.onClick({
    button: 0,
    preventDefault() {},
    currentTarget: { getAttribute: () => null },
  })
  await Vue.nextTick()
  assert.equal(router.currentRoute.value.path, '/coach')

  app.unmount()
  console.log('✓ renders two accessible primary home entries while hiding utility routes')
} finally {
  delete globalThis.__homeTestRouter
  delete globalThis.__homeTestVue
  delete globalThis.__homeTestIcons
  delete globalThis.__homeTestIdentity
  delete globalThis.__homeTestPage
}
