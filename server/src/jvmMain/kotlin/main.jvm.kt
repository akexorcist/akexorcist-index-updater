import kotlinx.coroutines.runBlocking
import shared.runServer

fun main(args: Array<String>): Unit = runBlocking {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 3000
    runServer(port)
}
