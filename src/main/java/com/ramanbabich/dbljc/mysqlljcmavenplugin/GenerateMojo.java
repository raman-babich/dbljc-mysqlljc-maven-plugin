/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ramanbabich.dbljc.mysqlljcmavenplugin;

import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.testcontainers.containers.MySQLContainer;
import org.twdata.maven.mojoexecutor.MojoExecutor;

/**
 * @author Raman Babich
 */
@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE)
@Execute(phase = LifecyclePhase.PROCESS_RESOURCES)
public class GenerateMojo extends AbstractMojo {

  private static final String THIS_PLUGIN_KEY = "com.ramanbabich.dbljc:mysqlljc-maven-plugin";
  private static final String MYSQL_DRIVER_NAME = "com.mysql.cj.jdbc.Driver";
  private static final String JOOQ_MYSQL_META = "org.jooq.meta.mysql.MySQLDatabase";
  private static final String LIQUIBASE_CONFIGURATION_ROOT_ELEMENT_NAME = "liquibaseConfiguration";
  private static final String JOOQ_CONFIGURATION_ROOT_ELEMENT_NAME = "jooqConfiguration";
  private static final String DEFAULT_ROOT_ELEMENT_NAME = "configuration";
  private static final String LIQUIBASE_MAVEN_PLUGIN_GOAL = "update";
  private static final String JOOQ_CODEGEN_MAVEN_PLUGIN_GOAL = "generate";

  @Parameter(defaultValue = "${project}", readonly = true)
  private MavenProject project;
  @Parameter(defaultValue = "${session}", readonly = true)
  private MavenSession session;
  @Component
  private BuildPluginManager buildPluginManager;

  @Parameter(name = "mysqlDockerImageName", defaultValue = "mysql:8.0.33")
  private String mysqlDockerImageName;

  @Parameter(name = "mysqlJdbcDriverVersion", defaultValue = "8.0.33")
  private String mysqlJdbcDriverVersion;

  @Parameter(name = "liquibaseMavenPluginVersion", defaultValue = "4.22.0")
  private String liquibaseMavenPluginVersion;

  @Parameter(name = "jooqCodegenMavenPluginVersion", defaultValue = "3.18.4")
  private String jooqCodegenMavenPluginVersion;

  @Override
  public void execute() throws MojoExecutionException {
    Plugin thisPlugin = project.getPlugin(THIS_PLUGIN_KEY);
    Xpp3Dom configuration = (Xpp3Dom) thisPlugin.getConfiguration();
    Xpp3Dom liquibaseConfiguration = renameElement(
        configuration.getChild(LIQUIBASE_CONFIGURATION_ROOT_ELEMENT_NAME),
        DEFAULT_ROOT_ELEMENT_NAME);
    Xpp3Dom jooqConfiguration = renameElement(
        configuration.getChild(JOOQ_CONFIGURATION_ROOT_ELEMENT_NAME),
        DEFAULT_ROOT_ELEMENT_NAME);

    try (MySQLContainer<?> mysql = new MySQLContainer<>(mysqlDockerImageName)) {
      mysql.start();
      MojoExecutor.executeMojo(
          liquibaseMavenPlugin(liquibaseMavenPluginVersion, mysqlJdbcDriverVersion),
          MojoExecutor.goal(LIQUIBASE_MAVEN_PLUGIN_GOAL),
          setLiquibaseDbConnectionValues(liquibaseConfiguration, mysql),
          MojoExecutor.executionEnvironment(project, session, buildPluginManager));
      MojoExecutor.executeMojo(
          jooqCodegenMavenPlugin(jooqCodegenMavenPluginVersion, mysqlJdbcDriverVersion),
          MojoExecutor.goal(JOOQ_CODEGEN_MAVEN_PLUGIN_GOAL),
          setJooqDbConnectionValues(jooqConfiguration, mysql),
          MojoExecutor.executionEnvironment(project, session, buildPluginManager));
    }
  }

  private static Xpp3Dom renameElement(Xpp3Dom element, String newName) {
    if (newName.equals(element.getName())) {
      return element;
    }
    return new Xpp3Dom(element, newName);
  }

  private static Plugin liquibaseMavenPlugin(String liquibaseMavenPluginVersion,
      String mysqlJdbcDriverVersion) {
    Plugin plugin = new Plugin();
    plugin.setGroupId("org.liquibase");
    plugin.setArtifactId("liquibase-maven-plugin");
    plugin.setVersion(liquibaseMavenPluginVersion);
    plugin.setDependencies(List.of(mysqlJdbcDriverDependency(mysqlJdbcDriverVersion)));
    return plugin;
  }

  private static Dependency mysqlJdbcDriverDependency(String version) {
    Dependency dependency = new Dependency();
    dependency.setGroupId("com.mysql");
    dependency.setArtifactId("mysql-connector-j");
    dependency.setVersion(version);
    return dependency;
  }

  private static Plugin jooqCodegenMavenPlugin(String jooqCodegenMavenPluginVersion,
      String mysqlJdbcDriverVersion) {
    Plugin plugin = new Plugin();
    plugin.setGroupId("org.jooq");
    plugin.setArtifactId("jooq-codegen-maven");
    plugin.setVersion(jooqCodegenMavenPluginVersion);
    plugin.setDependencies(List.of(mysqlJdbcDriverDependency(mysqlJdbcDriverVersion)));
    return plugin;
  }

  private static Xpp3Dom setLiquibaseDbConnectionValues(Xpp3Dom configuration,
      MySQLContainer<?> mysql) {
    getOrCreateChild(configuration, "driver").setValue(MYSQL_DRIVER_NAME);
    getOrCreateChild(configuration, "url").setValue(mysql.getJdbcUrl());
    getOrCreateChild(configuration, "username").setValue(mysql.getUsername());
    getOrCreateChild(configuration, "password").setValue(mysql.getPassword());
    return configuration;
  }

  private static Xpp3Dom setJooqDbConnectionValues(Xpp3Dom configuration,
      MySQLContainer<?> mysql) {
    Xpp3Dom generator = getOrCreateChild(configuration, "generator");
    Xpp3Dom database = getOrCreateChild(generator, "database");
    Xpp3Dom databaseName = getOrCreateChild(database, "name");
    databaseName.setValue(JOOQ_MYSQL_META);
    Xpp3Dom jdbc = getOrCreateChild(configuration, "jdbc");
    getOrCreateChild(jdbc, "driver").setValue(MYSQL_DRIVER_NAME);
    getOrCreateChild(jdbc, "url").setValue(mysql.getJdbcUrl());
    getOrCreateChild(jdbc, "username").setValue(mysql.getUsername());
    getOrCreateChild(jdbc, "password").setValue(mysql.getPassword());
    return configuration;
  }

  private static Xpp3Dom getOrCreateChild(Xpp3Dom element, String childName) {
    Xpp3Dom child = element.getChild(childName);
    if (child != null) {
      return child;
    }
    child = new Xpp3Dom(childName);
    element.addChild(child);
    return child;
  }

}
