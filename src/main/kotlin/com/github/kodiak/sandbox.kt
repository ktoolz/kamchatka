/*
 * kamchatka - KToolZ
 *
 * Copyright (c) 2016
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.github.kodiak

import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.core.Response


fun serversWithRandomness(rnd: Random = Random(), body: KronotskyNatureReserve<RandomResponse>.() -> Unit) =
        KronotskyNatureReserve({ containerRequestContect, executor ->
                                   RandomResponse(rnd, containerRequestContect, executor)
                               }).apply(body)



class RandomResponse(val rnd: Random, delegate: ContainerRequestContext, executors: ExecutorService) :
        MethodBody(delegate, executors) {
    fun randomResponse() = if (rnd.nextBoolean()) ok() else internalError()
}


class SlowRequest(val body: MethodBody.() -> Response) : (MethodBody) -> Response {
    override fun invoke(methodBody: MethodBody): Response =
            body(methodBody).apply { delay() }

    var currentDelay = 0L

    fun delay() {
        Thread.sleep(currentDelay)
    }
}

fun slow(body: MethodBody.() -> Response): SlowRequest = SlowRequest(body)

fun alwaysError(body: MethodBody.() -> Response): MethodBody.() -> Response = {
    val response = body()
    println("Replace $response by an error. Mouahahahhahahaha I'm evil... Deal with it")
    internalError()
}

class Delayer(var baseDelay: Long = 500, var deviation: Long = 100,
              var range: Pair<Long, Long> = 100L to 1000L) {
    fun Long.bound() = Math.max(range.first, Math.min(range.second, this))
    fun delay() {
        val duration = (baseDelay + (deviation * random.nextGaussian())).toLong()
        Thread.sleep(duration.bound())
    }
}

val random = Random(System.currentTimeMillis())

fun main(args: Array<String>) {

    val error: MethodBody.() -> Response = {
        println("Called with ${uriInfo.absolutePath} but internal error")
        internalError()
    }

    val delay2 = Delayer(250L)
    val timedBody = slow {
        data class SearchQuery(val query: String, val filter: String)
        readJSonBody<SearchQuery>()
        uriInfo
        delay2.delay()
        ok()
    }
    delay2.baseDelay += 500L
    timedBody.currentDelay += 500L

    var timer = 1000L

    val HelloWorld: MethodBody.() -> Response = {
        data class Message(val msg: String, val id: Int)
        ok(Message("Hello world!", 15))
    }


    val servers = serversWithRandomness {
        val hello: MethodBody.() -> Response = {
            data class Message(val msg: String, val id: Int)
            println("ok!")
            uriInfo
            after(250.ms) {
                println("this is after")
            }
            Thread.sleep(timer)
            ok(Message("Hello world!", 15))
        }

        server(host = "blabla", port = 650, start = true) {
            resource {
                get(body = HelloWorld)

                delete(body = alwaysError(hello))

                put(body = timedBody)
                post {
                    uriInfo
                    ok()
                    randomResponse()

                }
            }
        }
    }

    val list = servers.servers

    println(list)

    servers["foo"].singleResult().logFailure { println(message) }
    servers["foobar"].singleResult().logFailure { println(message) }
    with(servers) {
        server(port = 6540) {
            tagWith("foobar")

        }
    }
    servers["foobar"].singleResult().start()
            .logSuccess { println("Server started!") }
            .logFailure { println(message) }

    servers["foobar"].singleResult().apply { onSuccess { this } }

    println(list.filter { true })

    System.`in`.read()
    servers.shutdown(30, TimeUnit.SECONDS)
}