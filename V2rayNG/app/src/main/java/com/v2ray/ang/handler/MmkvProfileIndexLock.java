package com.v2ray.ang.handler;

import com.tencent.mmkv.MMKV;

/** Native boundary kept separate so JVM storage tests can substitute only the JNI lock calls. */
final class MmkvProfileIndexLock {
    private MmkvProfileIndexLock() {}

    static void lock(MMKV storage) {
        storage.lock();
    }

    static void unlock(MMKV storage) {
        storage.unlock();
    }
}
