package app.aaps.wear.di

/**
 * The scope of the watch app's companion graph ([WearGraph]).
 *
 * Not `AppScope`: Metro merges every `AppScope` contribution on the classpath into every `AppScope` graph,
 * and the watch app also carries the AAPS engine (`:wear:engine`), whose modules contribute hundreds of
 * bindings to `AppScope`. Its own scope keeps the companion graph to the watch app's own classes.
 */
abstract class WearScope private constructor()
