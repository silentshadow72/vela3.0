package dev.vela.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Resolves the hosted game Activity before starting it.
 *
 * The failure this exists to catch: launching opened the real Minecraft instead of
 * our hosted component. An explicit ComponentName should never reach another app,
 * so if resolution says otherwise, the component is not ours and the manifest is
 * the place to look — not a workaround.
 *
 * Nothing here falls back to launching the installed game. If the component does
 * not resolve to us, it returns an error that names the reason.
 */
object HostedActivity {

    data class Resolution(
        val ok: Boolean,
        val component: String,
        val resolvedPackage: String?,
        val reason: String?,
    )

    fun resolve(context: Context): Resolution {
        val intent = Intent().apply {
            component = ComponentName(context.packageName, VelaComponentFactory.GAME_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val flattened = intent.component?.flattenToString() ?: "(no component)"
        BoundaryLog.line(context.filesDir, "== hosted Activity resolution ==")
        BoundaryLog.line(context.filesDir, "our package: ${context.packageName}")
        BoundaryLog.line(context.filesDir, "component: $flattened")
        Reason.note(context.filesDir, "hosted component = $flattened")

        val resolved = try {
            context.packageManager.resolveActivity(intent, 0)
        } catch (t: Throwable) {
            BoundaryLog.line(context.filesDir, "resolveActivity threw ${t.javaClass.name}: ${t.message}")
            null
        }

        val resolvedPackage = resolved?.activityInfo?.packageName
        BoundaryLog.line(
            context.filesDir,
            "resolveActivity -> " + (resolvedPackage ?: "nothing"),
        )
        if (resolved != null) {
            BoundaryLog.line(
                context.filesDir,
                "resolved component: ${resolved.activityInfo?.name} in $resolvedPackage",
            )
        }

        // Check 3, stated plainly rather than worked around.
        if (resolvedPackage != null && resolvedPackage != context.packageName) {
            val reason =
                "Resolution returned $resolvedPackage, not ${context.packageName}. " +
                    "The component is not ours, so Android would fall through to the real " +
                    "app. The activity is missing from our merged manifest."
            BoundaryLog.line(context.filesDir, "RESULT: FAIL - $reason")
            Reason.result(
                context.filesDir,
                question = "does our hosted Activity component resolve to us?",
                attempt = "resolveActivity(ComponentName(${context.packageName}, ${VelaComponentFactory.GAME_ACTIVITY}))",
                observation = "resolved to $resolvedPackage",
                conclusion = "no - the component is not in our manifest",
            )
            return Resolution(false, flattened, resolvedPackage, reason)
        }

        if (resolvedPackage == null) {
            val reason =
                "Nothing resolved for $flattened. The activity is not declared in our " +
                    "manifest, or it is not enabled."
            BoundaryLog.line(context.filesDir, "RESULT: FAIL - $reason")
            Reason.result(
                context.filesDir,
                question = "does our hosted Activity component resolve to us?",
                attempt = "resolveActivity(...)",
                observation = "nothing resolved",
                conclusion = "no - the component is not declared or not enabled",
            )
            return Resolution(false, flattened, null, reason)
        }

        BoundaryLog.line(context.filesDir, "RESULT: OK - the component is ours")
        Reason.result(
            context.filesDir,
            question = "does our hosted Activity component resolve to us?",
            attempt = "resolveActivity(...)",
            observation = "resolved to $resolvedPackage",
            conclusion = "yes - the component belongs to this app",
        )
        return Resolution(true, flattened, resolvedPackage, null)
    }
}
