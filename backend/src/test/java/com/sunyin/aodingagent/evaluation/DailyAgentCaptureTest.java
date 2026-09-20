package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.stream.StreamSessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyAgentCaptureTest {
 @TempDir Path dir;
 @Test void rejectedWriteQueueDoesNotFailBusiness() {
  var env=new MockEnvironment().withProperty("app.agent-eval.enabled","true").withProperty("app.agent-eval.dir",dir.toString());
  var capture=new DailyAgentCapture(env, task -> {throw new java.util.concurrent.RejectedExecutionException();});
  var session=new StreamSessionRegistry().getOrStart("full-queue",s -> {
   capture.attach(s,"q","manus/chat"); s.emit("final","answer"); s.complete();
  });
  assertNotNull(session.finishedAt());
  assertEquals(2,capture.writeFailures());
  assertTrue(session.eventsAfter(0).collectList().block().stream().anyMatch(e -> "final".equals(e.event())));
 }

 @Test void secretsSplitAcrossStreamingChunksAreRedactedAtRest() throws Exception {
  var capture=enabledCapture(dir);
  new StreamSessionRegistry().getOrStart("chunked",s -> {
   capture.attach(s,"q","music_app/chat/sse");
   s.emit("message","sk-"); s.emit("message","splitsecret"); s.complete();
  });
  try(var files=Files.list(dir.resolve("raw"))) { assertFalse(Files.readString(files.findFirst().orElseThrow()).contains("sk-splitsecret")); }
 }

 @Test void writesAreDispatchedWithoutBlockingBusiness() throws Exception {
  var pendingWrites = new java.util.ArrayDeque<Runnable>();
  var env = new MockEnvironment().withProperty("app.agent-eval.enabled","true").withProperty("app.agent-eval.dir",dir.toString());
  var capture = new DailyAgentCapture(env, pendingWrites::add);
  var session = new StreamSessionRegistry().getOrStart("async-write", s -> {
   capture.attach(s,"q","manus/chat"); s.emit("final","answer"); s.complete();
  });
  assertNotNull(session.finishedAt());
  try (var files = Files.list(dir)) { assertEquals(0,files.count()); }
  assertEquals(2,pendingWrites.size());
  while (!pendingWrites.isEmpty()) pendingWrites.remove().run();
  try (var files = Files.list(dir.resolve("raw"))) { assertEquals("SUCCESS",new ObjectMapper().readTree(files.findFirst().orElseThrow().toFile()).path("terminalStatus").asText()); }
 }

 @Test void defaultsOnlyEnableExplicitLocalOrDevProfiles() throws Exception {
  int index = 0;
  for (String[] profiles : new String[][]{{"local"},{"dev"},{"local","dev"},{"prod"},{"local","prod"},{"test"}}) {
   Path output = dir.resolve("profiles-" + index++);
   var env = new MockEnvironment().withProperty("app.agent-eval.dir", output.toString());
   env.setActiveProfiles(profiles);
   var capture = new DailyAgentCapture(env, Runnable::run);
   new StreamSessionRegistry().getOrStart("request", s -> { capture.attach(s,"q","manus/chat"); s.complete(); });
   assertEquals(index <= 3, Files.exists(output));
  }
 }
 @Test void explicitDisableWinsOverLocalProfile() throws Exception {
  var env = new MockEnvironment().withProperty("app.agent-eval.enabled","false").withProperty("app.agent-eval.dir",dir.toString());
  env.setActiveProfiles("local");
  var capture = new DailyAgentCapture(env, Runnable::run);
  new StreamSessionRegistry().getOrStart("disabled", s -> { capture.attach(s,"q","manus/chat"); s.complete(); });
  try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
 }
 @Test void concurrentRunsRemainIsolatedAndReconnectDoesNotRecapture() throws Exception {
  var capture = new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled","true").withProperty("app.agent-eval.dir",dir.toString()));
  var registry = new StreamSessionRegistry();
  try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
   var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
   for (int i=0; i<40; i++) {
    int id=i;
    jobs.add(executor.submit(() -> registry.getOrStart("r"+id, s -> {
     capture.attach(s,"question-"+id,"manus/chat"); s.emit("final","answer-"+id); s.complete();
    })));
   }
   for (var job : jobs) job.get();
  }
  capture.awaitWrites();
  try (var files=Files.list(dir.resolve("raw"))) {
   var paths=files.toList(); assertEquals(40, paths.size());
   for (Path path:paths) {
    var row=new ObjectMapper().readTree(path.toFile());
    assertEquals(row.path("question").asText().replace("question-","answer-"),row.path("finalAnswer").asText());
   }
  }
  registry.getOrStart("r0", s -> fail("duplicate start"));
 }
 @Test void recordsCancellationFailuresFormsAndUnfinishedRuns() throws Exception {
  var capture=enabledCapture(dir); var registry=new StreamSessionRegistry();
  var cancelled=registry.getOrStart("cancel",s -> capture.attach(s,"q","music_app/chat/sse"));
  cancelled.cancel(); cancelled.complete();
  registry.getOrStart("error",s -> { capture.attach(s,"q","manus/chat"); throw new IllegalStateException("secret=x"); });
  registry.getOrStart("running",s -> capture.attach(s,"q","manus/chat"));
  registry.getOrStart("form",s -> { capture.attach(s,"q","manus/chat"); s.emit("trainingPlanForm","{}"); s.complete(); });
  registry.getOrStart("no-answer",s -> { capture.attach(s,"q","manus/chat"); s.complete(); });
  try (var files=Files.list(dir.resolve("raw"))) {
   var statuses=new java.util.HashSet<String>();
   for (Path path:files.toList()) statuses.add(new ObjectMapper().readTree(path.toFile()).path("terminalStatus").asText());
   assertEquals(java.util.Set.of("CANCELLED","ERROR","RUNNING","FORM_REQUIRED","NO_ANSWER"),statuses);
  }
 }
 @Test void unwritableDestinationDoesNotChangeChatResult() throws Exception {
  Path file=dir.resolve("not-directory"); Files.writeString(file,"fixture");
  var capture=enabledCapture(file);
  var session=new StreamSessionRegistry().getOrStart("io",s -> {
   capture.attach(s,"q","manus/chat"); s.emit("final","normal answer"); s.complete();
  });
  assertTrue(capture.writeFailures() > 0);
  assertEquals("normal answer",session.eventsAfter(0).collectList().block().getFirst().data());
 }
 @Test void redactsSecretsAndAudioAndBoundsTrace() throws Exception {
  var capture=enabledCapture(dir);
  String secret="Bearer abc.def sk-fake123 api_key=hidden password=hidden token=hidden data:audio/wav;base64,AAAA";
  new StreamSessionRegistry().getOrStart("privacy",s -> {
   capture.attach(s,secret,"manus/chat");
   s.emit("references",secret); s.emit("final",secret);
   for (int i=0;i<140;i++) s.emit("tool_result","x".repeat(1000));
   s.complete();
  });
  try (var files=Files.list(dir.resolve("raw"))) {
   String text=Files.readString(files.findFirst().orElseThrow());
   assertFalse(text.contains("abc.def")); assertFalse(text.contains("sk-fake123"));
   assertFalse(text.contains("hidden")); assertFalse(text.contains("AAAA"));
   var row=new ObjectMapper().readTree(text);
   assertTrue(row.path("truncated").asBoolean()); assertTrue(row.path("events").size()<=128);
  }
 }
 private DailyAgentCapture enabledCapture(Path path) {
  return new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled","true")
      .withProperty("app.agent-eval.dir",path.toString()), Runnable::run);
 }

 @Test void invalidConfigurationMustNotBreakChat() {
  var capture = new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled","not-a-boolean"), Runnable::run);
  var session = new StreamSessionRegistry().getOrStart("bad-config", s -> {
   capture.attach(s,"q","manus/chat"); s.emit("final","answer"); s.complete();
  });
  assertTrue(session.eventsAfter(0).collectList().block().stream().anyMatch(e -> "final".equals(e.event())));
 }

 @Test void emptyProfilesMustNotEnableCapture() throws Exception {
  var capture = new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.dir", dir.toString()), Runnable::run);
  new StreamSessionRegistry().getOrStart("disabled", s -> { capture.attach(s,"q","manus/chat"); s.complete(); });
  try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
 }
 @Test void captureDeadlineMustNotTerminateBusinessSession() throws Exception {
  var env = new MockEnvironment().withProperty("app.agent-eval.enabled","true")
      .withProperty("app.agent-eval.timeout-seconds","1").withProperty("app.agent-eval.dir",dir.toString());
  var capture = new DailyAgentCapture(env, Runnable::run);
  var session = new StreamSessionRegistry().getOrStart("long-running", s -> capture.attach(s,"q","manus/chat"));
  Thread.sleep(1300);
  assertNull(session.finishedAt(), "capture deadline must not set business finishedAt");
  try (var files = Files.list(dir.resolve("raw"))) {
   assertEquals("CAPTURE_TIMEOUT", new ObjectMapper().readTree(files.findFirst().orElseThrow().toFile()).path("terminalStatus").asText());
  }
  session.emit("final","business still works"); session.complete();
  var events = session.eventsAfter(0).collectList().block(java.time.Duration.ofSeconds(2));
  assertTrue(events.stream().anyMatch(e -> "final".equals(e.event())));
  assertFalse(events.stream().anyMatch(e -> "error".equals(e.event())));
 }

 @Test void capturesRealSessionOnceAcrossReconnectAndCancellation() throws Exception {
  var recorder = new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled","true").withProperty("app.agent-eval.dir", dir.toString()), Runnable::run);
  var registry = new StreamSessionRegistry();
  var session = registry.getOrStart("request", s -> {
   recorder.attach(s, "question", "manus");
   s.emit("step", "actual tool event"); s.emit("references", "[]"); s.emit("final", "answer"); s.complete();
  });
  registry.getOrStart("request", s -> fail("must not restart")); session.cancel();
  try(var files=Files.list(dir.resolve("raw"))) {
   var paths=files.filter(p -> p.toString().endsWith(".json")).toList(); assertEquals(1,paths.size());
   var row=new ObjectMapper().readTree(paths.getFirst().toFile());
   assertEquals("SUCCESS", row.path("terminalStatus").asText()); assertEquals("answer",row.path("finalAnswer").asText());
   assertEquals("UNLABELED",row.path("annotationStatus").asText()); assertEquals("NOT_JUDGED",row.path("judgeStatus").asText());
  }
 }
}
