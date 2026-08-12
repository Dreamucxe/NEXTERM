package com.nexterm.core.common

import javax.inject.Qualifier

/** Marks the dispatcher used for disk, filesystem and process work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Marks an application-scoped [kotlinx.coroutines.CoroutineScope]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
