package com.sunyin.aodingagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultipartUploadConfigurationTest {

    @Test
    void bindsExactTenMebibyteFileLimitWithOneMebibyteOfRequestHeadroom() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addFirst);
        MultipartProperties multipart = Binder.get(environment)
                .bind("spring.servlet.multipart", MultipartProperties.class)
                .orElseThrow(() -> new AssertionError("Multipart properties were not bound"));

        assertThat(multipart.getMaxFileSize().toBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(multipart.getMaxRequestSize().toBytes()).isEqualTo(11L * 1024 * 1024);
    }
}
