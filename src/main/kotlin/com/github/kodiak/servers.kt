package com.github.kodiak

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ktoolz.rezult.Result
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.server.model.Resource
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.TimeUnit.SECONDS
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.container.ContainerRequestFilter
import javax.ws.rs.container.ContainerResponseContext
import javax.ws.rs.container.ContainerResponseFilter
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.Response.Status.*
import javax.ws.rs.core.UriBuilder
import kotlin.reflect.KProperty

/**
 * Entry point of the builder
 */
fun servers(body: KronotskyNatureReserve<MethodBody>.() -> Unit) =
        KronotskyNatureReserve(::MethodBody).apply(body)

/**
 * Extension to start a server if the Result is a success
 */
fun Result<HttpServer>.start() = withSuccess { Result.of { start() } }

fun List<Result<HttpServer>>.start() = forEach { it.start() }

/**
 * Extension to immediatly shutdown a server if the Result is a success
 */
fun Result<HttpServer>.shutdownNow() = withSuccess { Result.of { shutdownNow() } }

fun List<Result<HttpServer>>.shutdownNow() = forEach { it.shutdownNow() }

/**
 * Extension to shutdown a server with a graceful period if the Result is a success
 */
fun Result<HttpServer>.shutdown(gracePeriod: Long, timeUnit: TimeUnit = SECONDS)
        = withSuccess { Result.of { shutdown(gracePeriod, timeUnit) } }

fun List<Result<HttpServer>>.shutdown(gracePeriod: Long, timeUnit: TimeUnit = SECONDS)
        = forEach { it.shutdown(gracePeriod, timeUnit) }

fun <T> List<Result<T>>.singleResult() = when (size) {
    0 -> Result.failure("Empty list")
    1 -> get(0)
    else -> Result.failure("More than one element")
}


/**
 * Kronotsky Nature Reserve is the home of Grizzly Bears.
 *
 */
class KronotskyNatureReserve<out T : ContainerRequestContext>(val provider: (ContainerRequestContext, ExecutorService) -> T) {

    private val executors: ExecutorService = Executors.newCachedThreadPool()

    private val mutableServerList = mutableListOf<Result<HttpServer>>()

    private val wrapperProvider: (ContainerRequestContext) -> T = { provider(it, executors) }

    /**
     * Return a read only list of servers. This list is *NOT* immutable.
     * Size can change over time if other servers are added.
     *
     * Use this one if you need an always up to date list of servers. Use [snapshot] if you need a snapshot of current
     * servers.
     *
     * Please also keep in mind that [HttpServer] is *not* immutable. Servers can be started or stopped.
     */
    val servers: List<Result<HttpServer>>
        get() = mutableServerList

    /**
     * Return a snapshot of all servers at current type. Whereas [servers] this list
     * is Immutable, size will not change over time.
     *
     * Use this one if you need a snapshot of current servers. Use [servers] if you need an always up to date list of
     * servers.
     *
     * Please also keep in mind that [HttpServer] is *not* immutable. Servers can be started or stopped.
     */
    val snapshot: List<Result<HttpServer>>
        get() = mutableServerList.toList()


    companion object {

        val Result<HttpServer>.tags: MutableSet<String> by object {
            private val allTags: MutableMap<Result<HttpServer>, MutableSet<String>> = IdentityHashMap(20)

            operator fun getValue(receiver: Result<HttpServer>, metadata: KProperty<*>): MutableSet<String> =
                    allTags.getOrPut(receiver, { mutableSetOf<String>() })
        }

    }

    fun createServer(scheme: String = "http",
                     host: String = "0.0.0.0",
                     port: Int,
                     path: String = "/",
                     start: Boolean = false,
                     body: ResourceConfigBuilder<T>.() -> Unit): Result<HttpServer> =
            Result.of { ResourceConfigBuilder(wrapperProvider).apply(body) }
                    .onSuccess { resourceConfigBuilder ->
                        Result.of {
                            val uri = UriBuilder.fromPath(path).host(host).port(port).scheme(scheme).build()
                            GrizzlyHttpServerFactory.createHttpServer(uri, resourceConfigBuilder.rc, start)!!
                        }.apply {
                            tags += resourceConfigBuilder.tags
                            // add some tags based on the URI so users can search a server using the scheme or the port
                            tags += port.toString()
                            tags += scheme
                            tags += host
                            if (isFailure()) tags += "error"
                        }
                    }
                    .onFailure { exception ->
                        Result.failure<HttpServer>(exception).apply {
                            tags += "error"
                        }
                    }

    fun server(scheme: String = "http",
               host: String = "0.0.0.0",
               port: Int,
               path: String = "/",
               start: Boolean = false,
               body: ResourceConfigBuilder<T>.() -> Unit) {

        mutableServerList += createServer(scheme, host, port, path, start, body)
    }

    fun start() = mutableServerList.start()

    fun shutdownNow() {
        executors.shutdownNow()
        mutableServerList.shutdownNow()
    }

    fun shutdown(gracePeriod: Long, timeUnit: TimeUnit = SECONDS) {
        with(executors) {
            shutdown()
            awaitTermination(gracePeriod, timeUnit)
        }
        mutableServerList.shutdown(gracePeriod, timeUnit)
    }

    operator fun get(tag: String) = mutableServerList.filter { it.tags.contains(tag) }

    fun start(tag: String) = get(tag).start()

    fun shutdownNow(tag: String) = get(tag).shutdownNow()

    fun shutdown(tag: String, gracePeriod: Long, timeUnit: TimeUnit = SECONDS)
            = get(tag).shutdown(gracePeriod, timeUnit)

}



/**
 * Builder for [ResourceConfig]
 */
class ResourceConfigBuilder<out T : ContainerRequestContext>(val wrapperProvider: (ContainerRequestContext) -> T) {

    val rc = ResourceConfig()
    val tags = mutableSetOf<String>()

    fun resource(rootPath: String = "/", body: ResourceBuilder<T>.() -> Unit) {
        rc.registerResources(ResourceBuilder(rootPath, wrapperProvider).apply(body).build())
    }

    fun tagWith(vararg tag: String) {
        tags += tag
    }

    fun requestFilter(body: (ContainerRequestContext) -> Unit) {
        rc.register(ContainerRequestFilter { body(it) })
    }

    fun responseFilter(body: (ContainerRequestContext, ContainerResponseContext) -> Unit) {
        rc.register(ContainerResponseFilter { requestContext, responseContext ->
            body(requestContext!!, responseContext!!)
        })
    }

}

/**
 * Builder for [Resource]
 */
class ResourceBuilder<out T : ContainerRequestContext>(rootPath: String, val wrapperProvider: (ContainerRequestContext) -> T) {

    val resourceBuilder = Resource.builder(rootPath)!!


    fun put(relativePath: String = "/",
            consume: MediaType = MediaType.APPLICATION_JSON_TYPE,
            produce: MediaType = consume,
            body: T.() -> Response)
            = createChildResource("PUT", relativePath, consume, produce, body)


    fun get(relativePath: String = "/",
            consume: MediaType = MediaType.APPLICATION_JSON_TYPE,
            produce: MediaType = consume,
            body: T.() -> Response)
            = createChildResource("GET", relativePath, consume, produce, body)

    fun post(relativePath: String = "/",
             consume: MediaType = MediaType.APPLICATION_JSON_TYPE,
             produce: MediaType = consume,
             body: T.() -> Response)
            = createChildResource("POST", relativePath, consume, produce, body)

    fun delete(relativePath: String = "/",
               consume: MediaType = MediaType.APPLICATION_JSON_TYPE,
               produce: MediaType = consume,
               body: T.() -> Response)
            = createChildResource("DELETE", relativePath, consume, produce, body)

    private fun createChildResource(method: String,
                                    relativePath: String,
                                    consume: MediaType = MediaType.APPLICATION_JSON_TYPE,
                                    produce: MediaType = consume,
                                    body: T.() -> Response) =
            resourceBuilder.addChildResource(relativePath)
                    .addMethod(method)
                    .consumes(consume)
                    .produces(produce)
                    // Do NOT write like this, subtle bug in handledBy will cause NPE
                    // .handledBy(body)!!
                    .handledBy { containerRequestContext -> wrapperProvider(containerRequestContext).body() }!!


    fun build(): Resource? = resourceBuilder.build()
}

open class MethodBody(delegate: ContainerRequestContext, private val executors: ExecutorService) :
        ContainerRequestContext by delegate {

    val decoder: ObjectMapper = ObjectMapper()
    inline fun <reified T : Any> readJSonBody(): T
            = decoder.readValue(entityStream, T::class.java)

    fun response(status: Status = OK, entity: Any? = null) =
            Response.status(status).entity(entity).build()!!

    fun response(code: Int = 200, entity: Any? = null) = Response.status(code).entity(entity).build()!!
    fun ok(entity: Any? = null) = response(OK, entity)
    fun noContent() = response(NO_CONTENT)
    fun notFound() = response(NOT_FOUND)
    fun internalError() = response(INTERNAL_SERVER_ERROR)

    // Some extended values to be used with [after]
    val Number.ms: Duration get() = Duration.of(this.toLong(), ChronoUnit.MILLIS)
    val Number.s: Duration get() = Duration.of(this.toLong(), ChronoUnit.SECONDS)
    val Number.m: Duration get() = Duration.of(this.toLong(), ChronoUnit.MINUTES)
    fun <T> after(duration: Duration, run: () -> T): Future<T> =
            executors.submit(Callable {
                Thread.sleep(duration.toMillis())
                run()
            })
}

