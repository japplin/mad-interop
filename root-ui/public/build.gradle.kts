plugins {
  alias(libs.plugins.kotlin.jvm)
  id("mad.di")
}

dependencies {
  implementation(project(":scopes:public"))
  implementation(project(":use-cases:feature-with-graph:public"))
}