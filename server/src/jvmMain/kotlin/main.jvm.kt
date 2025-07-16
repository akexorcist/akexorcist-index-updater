import kotlinx.coroutines.runBlocking
import shared.runServer

fun main(args: Array<String>): Unit = runBlocking {
    val port = args.getOrNull(1)?.toIntOrNull() ?: 3001
    runServer(port)
}
