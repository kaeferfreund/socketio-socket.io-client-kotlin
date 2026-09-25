package io.github.kaeferfreund.socketio.android

import android.net.Network
import android.net.TrafficStats
import okhttp3.Dns
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * The network a manager's connections are bound to. Sockets and DNS lookups
 * go through [network] when one is set (`Network.socketFactory` and
 * `Network.getAllByName`), so a connection belongs to exactly one network and
 * breaks cleanly when that network goes away instead of silently hanging.
 *
 * A lookup that fails on the bound network falls back to the system resolver:
 * some networks answer bound lookups with EAI_NODATA without sending a query
 * (issue #1, a Tailscale VPN), while the system resolver answers.
 */
internal class NetworkBinding(
    private val trafficStatsTag: Int?,
    /** DNS while no network is bound, and the fallback for a failed bound lookup (the base client's). */
    private val systemDns: Dns = Dns.SYSTEM,
    /** The socket factory while no network is bound (the base client's). */
    private val defaultSocketFactory: SocketFactory = SocketFactory.getDefault(),
    private val lookupOnNetwork: (Network, String) -> List<InetAddress> = { network, host -> network.getAllByName(host).toList() },
) {
    @Volatile var network: Network? = null

    val socketFactory: SocketFactory =
        object : SocketFactory() {
            private fun delegate(): SocketFactory = network?.socketFactory ?: defaultSocketFactory

            private fun tagged(socket: Socket): Socket {
                val tag = trafficStatsTag ?: return socket
                val previous = TrafficStats.getThreadStatsTag()
                TrafficStats.setThreadStatsTag(tag)
                try {
                    TrafficStats.tagSocket(socket)
                } finally {
                    TrafficStats.setThreadStatsTag(previous)
                }
                return socket
            }

            override fun createSocket(): Socket = tagged(delegate().createSocket())

            override fun createSocket(
                host: String?,
                port: Int,
            ): Socket = tagged(delegate().createSocket(host, port))

            override fun createSocket(
                host: String?,
                port: Int,
                localHost: InetAddress?,
                localPort: Int,
            ): Socket = tagged(delegate().createSocket(host, port, localHost, localPort))

            override fun createSocket(
                host: InetAddress?,
                port: Int,
            ): Socket = tagged(delegate().createSocket(host, port))

            override fun createSocket(
                address: InetAddress?,
                port: Int,
                localAddress: InetAddress?,
                localPort: Int,
            ): Socket = tagged(delegate().createSocket(address, port, localAddress, localPort))
        }

    val dns: Dns =
        Dns { hostname ->
            val bound = network ?: return@Dns systemDns.lookup(hostname)
            try {
                lookupOnNetwork(bound, hostname)
            } catch (e: UnknownHostException) {
                try {
                    systemDns.lookup(hostname)
                } catch (fallback: UnknownHostException) {
                    fallback.addSuppressed(e)
                    throw fallback
                }
            }
        }
}
