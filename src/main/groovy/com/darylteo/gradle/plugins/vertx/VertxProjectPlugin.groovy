package com.darylteo.gradle.plugins.vertx

import groovy.json.*

import org.gradle.api.*
import org.gradle.api.tasks.Copy
import org.gradle.plugins.ide.idea.IdeaPlugin

import com.darylteo.gradle.plugins.vertx.handlers.VertxPropertiesHandler

/**
 * Plugin responsible for configuring a vertx enabled project
 *
 * Required Properties
 *  * vertxVersion - version of Vertx to use
 * @author Daryl Teo
 */
class VertxProjectPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.convention.plugins.projectPlugin = new ProjectPluginConvention(project)

    configureProject project
    registerIncludes project
    addModuleTasks project
  }

  private void configureProject(Project project) {
    project.beforeEvaluate {
      project.with {
        println "Configuring $it"

        // configure language plugins
        if(vertx.language in ['java', 'groovy', 'scala']){
          apply plugin: vertx.language
        } else {
          apply plugin: 'java'
        }

        sourceCompatibility = '1.7'
        targetCompatibility = '1.7'

        repositories {
          mavenCentral()
          maven { url 'https://oss.sonatype.org/content/repositories/snapshots' }
        }

        configurations {
          vertxdeps

          vertxcore     // holds all core vertx jars
          vertxincludes // holds all included modules
          vertxlibs     // holds all libs from included modules

          vertxdeps.extendsFrom vertxcore
          vertxdeps.extendsFrom vertxincludes
          vertxdeps.extendsFrom vertxlibs

          compile.extendsFrom vertxdeps
        }

        /* Module Configuration */
        dependencies {
          vertxcore "io.vertx:vertx-core:${vertx.version}"
          vertxcore "io.vertx:vertx-platform:${vertx.version}"

          vertxcore "io.vertx:testtools:${vertx.version}"
        }

        // Configuring Classpath
        sourceSets {
          all { compileClasspath += configurations.vertxdeps }
        }

        // Map the 'provided' dependency configuration to the appropriate IDEA visibility scopes.
        plugins.withType(IdeaPlugin) {
          idea {
            module {
              scopes.PROVIDED.plus += configurations.vertxdeps
              scopes.COMPILE.minus += configurations.vertxdeps
              scopes.TEST.minus += configurations.vertxdeps
              scopes.RUNTIME.minus += configurations.vertxdeps
            }
          }
        }
      }
    }
  }

  private void addModuleTasks(Project project){
    project.beforeEvaluate {
      project.with {
        task('generateModJson') {
          def confdir = file("$buildDir/conf")
          def modjson = file("$confdir/mod.json")
          outputs.file modjson

          doLast{
            confdir.mkdirs()
            modjson.createNewFile()

            modjson << JsonOutput.toJson(vertx.config)
          }
        }

        task('copyMod', dependsOn: [
          classes,
          generateModJson
        ], type: Copy) {
          group = 'vert.x'
          description = 'Assemble the module into the local mods directory'

          into rootProject.file("mods/${project.moduleName}")

          sourceSets.all {
            if (it.name != 'test'){
              from it.output
            }
          }
          from generateModJson

          // and then into module library directory
          into ('lib') {
            from configurations.compile
            exclude { it.file in configurations.vertxdeps.files }
          }

        }

        test { dependsOn copyMod }
      }

    }
  }

  private void registerIncludes(Project project) {
    project.beforeEvaluate{
      project.with {
        task('copyIncludedMods') {
          group = 'vert.x'
          description = 'Copies all included Vert.x modules into the local mods folder'
        }

        build.dependsOn copyIncludedMods
      }
    }

    project.afterEvaluate {
      project.with {
        dependencies {
          vertx.config?.includes?.each {
            vertxincludes convertNotation(it)
          }

          configurations.vertxincludes.each {
            vertxlibs project.zipTree(it).matching { include : 'lib/*.jar' }
          }
        }
      }
    }
  }

  private String convertNotation(String notation) {
    def (group, name, version) = notation.split('~')

    return "$group:$name:$version@zip"
  }

  private class ProjectPluginConvention {
    private Project project
    private VertxPropertiesHandler properties

    ProjectPluginConvention(Project project){
      this.project = project
      this.properties = new VertxPropertiesHandler()
    }

    String getModuleName() {
      return "${project.group}~${project.name}~${project.version}"
    }

    def vertx(Closure closure) {
      closure.setDelegate(properties)
      closure.resolveStrategy = Closure.DELEGATE_FIRST
      closure(properties)
    }

    def getVertx() {
      return properties
    }
  }

}
