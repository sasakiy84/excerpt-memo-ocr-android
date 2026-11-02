buildscript {
    configurations.classpath { resolutionStrategy.activateDependencyLocking() }
}

plugins { id("com.android.application") version "9.0.1" apply false }
