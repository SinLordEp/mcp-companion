package com.github.sinlordep.mcpcompanion.common.build.externalsystem

import com.github.sinlordep.mcpcompanion.common.build.ide.BuildToolTaskProvider
import com.intellij.build.BuildProgressListener
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration

/**
 * A build delegated to Gradle (or another external system) runs as an [ExternalSystemRunConfiguration] whose
 * progress goes to the Build tool window: the IDE's build runner sets
 * [ExternalSystemRunConfiguration.PROGRESS_LISTENER_KEY] on it. An application or test that Gradle runs for the IDE,
 * and a task run from the Gradle tool window, are external-system configurations without it (seen end to end: the
 * Gradle-delegated run of `Beacon` during a build was read until this check).
 */
class ExternalSystemBuildTasks : BuildToolTaskProvider {
    override fun isBuildToolTask(profile: RunProfile): Boolean = profile is ExternalSystemRunConfiguration &&
        profile.getUserData<Class<out BuildProgressListener>>(ExternalSystemRunConfiguration.PROGRESS_LISTENER_KEY) !=
        null
}
