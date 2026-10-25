package com.phantom.hooker;

import java.lang.reflect.Method;

/**
 * LSPlant hooker object.
 *
 * LSPlant contract (v1 API):
 *   - Hook(env, target_method, hooker_object, callback_method)
 *   - callback_method must be an instance method of hooker_object's class
 *     with the exact signature:  public Object callback(Object[] args)
 *   - Hook() returns the "backup" Method. Invoking it calls the original
 *     implementation.
 *
 * For instance methods args[0] is the `this` receiver, followed by the
 * declared parameters (boxed). For static methods args contains only the
 * declared parameters. Primitive returns of the callback are unboxed
 * automatically by LSPlant's generated bridge.
 *
 * One PhantomHooker instance is created per hooked method. `hookId`
 * identifies which hook fired (see HookBridge.H_* constants) and `backup`
 * is filled in by the native module right after Hook() succeeds.
 */
public class PhantomHooker {

    /** Backup (original) Method, set by native code after lsplant::Hook(). */
    public Method backup;

    /** One of HookBridge.H_* constants identifying this hook. */
    public int hookId;

    /** Entry point invoked by LSPlant's generated stub for every call. */
    public Object callback(Object[] args) throws Throwable {
        return HookBridge.dispatch(hookId, backup, args);
    }
}
