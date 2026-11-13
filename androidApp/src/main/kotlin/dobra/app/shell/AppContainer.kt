package dobra.app.shell

/**
 * The one place that creates and hands out the app's long-lived objects (repositories, the worker, the AI jobs).
 * No dependency-injection framework: each feature adds its object here as a property and screens receive the
 * container from [AppNav]. Empty until a feature needs it.
 */
class AppContainer
