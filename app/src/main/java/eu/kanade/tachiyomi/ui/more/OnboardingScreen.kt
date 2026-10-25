package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.onboarding.OnboardingScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tutorial.TutorialStep
import mihon.app.di.appGraph
import tachiyomi.presentation.core.util.collectAsState

class OnboardingScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val basePreferences = remember { context.appGraph.basePreferences }
        val shownOnboardingFlow by basePreferences.shownOnboardingFlow.collectAsState()

        val finishOnboarding: () -> Unit = {
            if (!basePreferences.shownOnboardingFlow.get()) {
                basePreferences.tutorialStep.set(TutorialStep.Start)
            }
            basePreferences.shownOnboardingFlow.set(true)
            navigator.pop()
        }

        BackHandler(enabled = !shownOnboardingFlow) {
            // Prevent exiting if onboarding hasn't been completed
        }

        OnboardingScreen(onComplete = finishOnboarding)
    }
}
