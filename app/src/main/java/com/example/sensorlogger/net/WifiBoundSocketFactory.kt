package com.example.sensorlogger.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import timber.log.Timber

/**
 * SocketFactory que garante que todas as conexões sejam vinculadas à rede Wi-Fi ativa.
 * Caso o dispositivo não esteja em Wi-Fi, uma IOException é lançada para que o chamador
 * possa optar por enfileirar o envio offline.
 */
class WifiBoundSocketFactory private constructor(
    private val appContext: Context
) : SocketFactory() {

    private val connectivityManager: ConnectivityManager
        get() = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Throws(IOException::class)
    private fun requireWifiNetwork(): Network {
        val cm = connectivityManager

        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            cm.getNetworkCapabilities(activeNetwork)?.let { caps ->
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return activeNetwork
                }
            }
        }

        val fallback = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        return fallback ?: throw IOException("No Wi-Fi network available")
    }

    @Throws(IOException::class)
    private fun bindToWifi(socket: Socket): Socket {
        val wifiNetwork = requireWifiNetwork()
        runCatching {
            wifiNetwork.bindSocket(socket)
        }.onFailure { throwable ->
            Timber.w(throwable, "Failed to bind socket to Wi-Fi network")
            throw IOException("Failed to bind socket to Wi-Fi", throwable)
        }
        return socket
    }

    @Throws(IOException::class)
    fun bindSocket(socket: Socket): Socket = bindToWifi(socket)

    override fun createSocket(): Socket {
        return bindToWifi(Socket())
    }

    override fun createSocket(host: String, port: Int): Socket {
        return createSocket().apply {
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(address: InetAddress, port: Int): Socket {
        return createSocket().apply {
            connect(InetSocketAddress(address, port))
        }
    }

    override fun createSocket(
        host: String,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket {
        return createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket {
        return createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
    }

    companion object {
        @Volatile
        private var instance: WifiBoundSocketFactory? = null

        fun get(context: Context): WifiBoundSocketFactory {
            return instance ?: synchronized(this) {
                instance ?: WifiBoundSocketFactory(context.applicationContext).also { instance = it }
            }
        }
    }
}
