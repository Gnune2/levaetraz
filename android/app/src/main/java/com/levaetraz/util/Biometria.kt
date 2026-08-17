package com.levaetraz.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine

/** true se o aparelho tem hardware de biometria e algo cadastrado. */
fun temBiometria(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
        BiometricManager.BIOMETRIC_SUCCESS

/**
 * Mostra o prompt e suspende até o usuário confirmar, cancelar ou errar
 * demais. Só devolve true quando a autenticação realmente passou.
 */
suspend fun autenticar(
    activity: FragmentActivity,
    titulo: String = "levaetraz",
    subtitulo: String = "confirme para abrir",
): Boolean = suspendCancellableCoroutine { cont ->
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resumeWith(Result.success(true))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resumeWith(Result.success(false))
            }
        },
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(titulo)
        .setSubtitle(subtitulo)
        .setNegativeButtonText("cancelar")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()

    cont.invokeOnCancellation { prompt.cancelAuthentication() }
    prompt.authenticate(info)
}
