package io.github.derundevu.yaxc.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Refuses to run destructive fixtures against the owner's installed application. */
class RegressionTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == "io.github.derundevu.yaxc.regression") {
            "Instrumentation requires the isolated regression build, never the installed user app"
        }
        return super.newApplication(loader, className, context)
    }
}
