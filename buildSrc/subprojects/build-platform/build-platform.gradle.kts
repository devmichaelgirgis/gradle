/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * This project defines the "Gradle buildSrc platform", which is the set of constraints that are shared
 * by all buildSrc subprojects. We're abusing the fact that this project has the java-library project
 * applied. Ideally we'd need a "java-platform" plugin applied, but this thing is just not yet ready.
 */

// Here you should declare versions which should be shared by the different modules of buildSrc itself
val javaParserVersion = "3.6.11"

dependencies {
    constraints {
        api("com.github.javaparser:javaparser-core:$javaParserVersion")
        api("com.github.javaparser:javaparser-symbol-solver-core:$javaParserVersion")
    }
}
