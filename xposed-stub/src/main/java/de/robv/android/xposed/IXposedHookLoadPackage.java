package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Compile-time stub of the Xposed API (rovo89, v82). The real classes come from the framework at runtime. */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
