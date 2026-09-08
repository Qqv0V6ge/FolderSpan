package com.folderspan.service.http.server.linkshare

typealias LinkShareHttpHandler = suspend (LinkShareHttpExchange) -> LinkShareHttpResponse

class LinkShareHttpExchange(
    val request: LinkShareHttpRequest,
) {
    private val attributes = mutableMapOf<LinkShareHttpAttributeKey<*>, Any?>()

    fun response(block: LinkShareHttpResponseBuilder.() -> Unit): LinkShareHttpResponse {
        return LinkShareHttpResponseBuilder().apply(block).build()
    }

    fun <T : Any> put(key: LinkShareHttpAttributeKey<T>, value: T) {
        attributes[key] = value
    }

    fun <T : Any> getOrNull(key: LinkShareHttpAttributeKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return attributes[key] as? T
    }
}

class LinkShareHttpAttributeKey<T : Any>(val name: String)

class LinkShareHttpRouter private constructor(
    private val routes: List<Route>,
    private val notFoundHandler: LinkShareHttpHandler,
) {
    suspend fun dispatch(request: LinkShareHttpRequest): LinkShareHttpResponse {
        val exchange = LinkShareHttpExchange(request)
        return dispatch(exchange)
    }

    suspend fun dispatch(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val request = exchange.request
        val route = routes.firstOrNull { route -> route.matches(request) }
        return (route?.handler ?: notFoundHandler).invoke(exchange)
    }

    class Builder {
        private val routes = mutableListOf<Route>()
        private var notFoundHandler: LinkShareHttpHandler = {
            LinkShareHttpResponse.text(statusCode = 404, text = "Not Found")
        }

        fun exact(method: String, path: String, handler: LinkShareHttpHandler) {
            routes += Route(
                method = method.uppercase(),
                matcher = RouteMatcher.Exact(normalizeRoutePath(path)),
                handler = handler,
            )
        }

        fun prefix(method: String, prefix: String, handler: LinkShareHttpHandler) {
            routes += Route(
                method = method.uppercase(),
                matcher = RouteMatcher.Prefix(normalizeRoutePath(prefix).trimEnd('/')),
                handler = handler,
            )
        }

        fun root(method: String, handler: LinkShareHttpHandler) {
            exact(method, "/", handler)
        }

        fun fallback(method: String, handler: LinkShareHttpHandler) {
            routes += Route(
                method = method.uppercase(),
                matcher = RouteMatcher.Fallback,
                handler = handler,
            )
        }

        fun build(): LinkShareHttpRouter {
            return LinkShareHttpRouter(routes.toList(), notFoundHandler)
        }
    }

    private data class Route(
        val method: String,
        val matcher: RouteMatcher,
        val handler: LinkShareHttpHandler,
    ) {
        fun matches(request: LinkShareHttpRequest): Boolean {
            return request.method.equals(method, ignoreCase = true) && matcher.matches(request.path)
        }
    }

    private sealed class RouteMatcher {
        abstract fun matches(path: String): Boolean

        data class Exact(private val path: String) : RouteMatcher() {
            override fun matches(path: String): Boolean = this.path == normalizeRoutePath(path)
        }

        data class Prefix(private val prefix: String) : RouteMatcher() {
            override fun matches(path: String): Boolean {
                val normalizedPath = normalizeRoutePath(path)
                return normalizedPath == prefix || normalizedPath.startsWith("$prefix/")
            }
        }

        data object Fallback : RouteMatcher() {
            override fun matches(path: String): Boolean = true
        }
    }

    companion object {
        fun build(block: Builder.() -> Unit): LinkShareHttpRouter {
            return Builder().apply(block).build()
        }
    }
}

private fun normalizeRoutePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return "/"
    val prefixed = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    return if (prefixed.length > 1) prefixed.trimEnd('/') else prefixed
}
