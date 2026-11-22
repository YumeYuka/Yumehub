package moe.yumeyuka.yumehub.feature.auth

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import org.koin.androidx.compose.koinViewModel

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    addingAccount: Boolean,
    modifier: Modifier = Modifier,
) {
    val model: LoginViewModel = koinViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            model.handleResult(it.data)
        }
    val context = LocalContext.current
    val failureMessage = LocalGithubStrings.current.Profile.AccountFailed
    LaunchedEffect(state) {
        when (state) {
            LoginState.Completed -> onLoggedIn()
            LoginState.Failed -> {
                Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show()
                model.acknowledgeFailure()
            }
            else -> Unit
        }
    }
    GithubBackHandler(enabled = state == LoginState.Completing) {}
    LoginContent(modifier, addingAccount, state) { model.start { launcher.launch(it) } }
}

@Composable
private fun LoginContent(
    modifier: Modifier,
    addingAccount: Boolean,
    state: LoginState,
    onLogin: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val titleInset = if (maxHeight < 420.dp) 24.dp else maxHeight * 0.44f
        Column(
            modifier =
                Modifier.widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                if (titleInset > 24.dp) {
                    LoginDotField(
                        Modifier.fillMaxWidth().height(titleInset).padding(vertical = 12.dp),
                    )
                } else {
                    Spacer(Modifier.height(titleInset))
                }
                Text(
                    text = if (addingAccount) strings.Profile.AddAccount else strings.Login.Title,
                    modifier = Modifier.semantics { heading() },
                    style = GlasmioTheme.textStyles.title2,
                    fontWeight = FontWeight.Normal,
                    color = GlasmioTheme.colors.content,
                )
                Text(
                    text = strings.Login.AccountHint,
                    style = GlasmioTheme.textStyles.title2,
                    fontWeight = FontWeight.Normal,
                    color = GlasmioTheme.colors.secondaryContent,
                )
            }
            if (titleInset > 24.dp) {
                LoginDotField(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    belowTitle = true,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LoginAction(state, onLogin)
                Spacer(Modifier.height(14.dp))
                LoginLegalLinks()
            }
        }
    }
}

@Composable
private fun LoginAction(state: LoginState, onClick: () -> Unit) {
    val strings = LocalGithubStrings.current.Login
    GlasmioButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = state == LoginState.Ready,
        emphasis = GlassEmphasis.Prominent,
        navigationSurfaceColor = GlasmioTheme.colors.content,
        size = GlasmioButtonSize.Large,
    ) {
        GithubLoadingContent(
            loading = state != LoginState.Ready && state != LoginState.Failed,
            placeholder = {
                TextLinePlaceholder(
                    GlasmioTheme.textStyles.body,
                    Modifier.width(112.dp),
                    fraction = 1f,
                )
            },
        ) {
            Text(
                text = strings.SignIn,
                style = GlasmioTheme.textStyles.body,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoginLegalLinks() {
    val openExternal = rememberExternalUriHandler()
    val strings = LocalGithubStrings.current
    val termsUrl = "https://docs.github.com/site-policy/github-terms/github-terms-of-service"
    val privacyUrl = "https://docs.github.com/site-policy/privacy-policies/github-privacy-statement"
    val linkStyles =
        TextLinkStyles(
            style =
                SpanStyle(
                    color = GlasmioTheme.colors.content,
                    textDecoration = TextDecoration.Underline,
                ),
        )
    Text(
        text =
            buildAnnotatedString {
                withLink(
                    LinkAnnotation.Url(
                        termsUrl,
                        linkStyles,
                        linkInteractionListener = { openExternal(termsUrl) },
                    ),
                ) {
                    append(strings.Login.Terms)
                }
                append(strings.Login.LegalSeparator)
                withLink(
                    LinkAnnotation.Url(
                        privacyUrl,
                        linkStyles,
                        linkInteractionListener = { openExternal(privacyUrl) },
                    ),
                ) {
                    append(strings.Login.Privacy)
                }
            },
        style = GlasmioTheme.textStyles.footnote,
        color = GlasmioTheme.colors.secondaryContent,
        textAlign = TextAlign.Center,
    )
}
