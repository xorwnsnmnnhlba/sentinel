package com.sentinel.application.drift;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaDriftProperties.class)
class SchemaDriftConfig {

}
