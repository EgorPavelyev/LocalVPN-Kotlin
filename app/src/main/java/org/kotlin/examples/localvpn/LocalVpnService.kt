package org.kotlin.examples.localvpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.whileSelect
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.namednumber.IpNumber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class LocalVpnService : VpnService() {
    companion object {
        private const val TAG = "LocalVpnService"
    }

    private val closeCh = Channel<Unit>()
    private val inputCh = Channel<IpV4Packet>()

    private var vpnInterface: ParcelFileDescriptor? = null

    private var udpVpnService: UdpVpnService? = null
    private var tcpVpnService:TcpVpnService?=null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            stopVpn()
        }
        return Service.START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        setupVpn()
        // Initialize all services for VPN.
        udpVpnService = UdpVpnService(this, inputCh, closeCh)
        udpVpnService!!.start()
        tcpVpnService= TcpVpnService(this,inputCh,closeCh)
        tcpVpnService!!.start()
        startVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelf()
    }

    private fun setupVpn() {
        var builder = Builder()
                .addAddress("10.0.2.15", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setSession(TAG)
        vpnInterface = builder.establish()
        Log.d(TAG, "VPN interface has established")
    }

    private fun startVpn() {
        launch { vpnRunLoop() }
    }

    suspend fun vpnRunLoop() {
        Log.d(TAG, "running loop")
        var alive = true

        // Receive from local and send to remote network.
        val vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor).channel
        // Receive from remote and send to local network.
        val vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor).channel

        launch {
            // TODO: should be take BufferPool.
            loop@ while (alive) {
                val buffer = ByteBuffer.allocate(1024)
                val readBytes = vpnInputStream.read(buffer)
                if (readBytes <= 0) {
                    delay(100)
                    continue@loop
                }
                val packet = IpV4Packet.newPacket(buffer.array(), 0, readBytes)
                Log.d(TAG, "REQUEST\n${packet}")
                when (packet.header.protocol) {
                    IpNumber.UDP -> {
                        udpVpnService!!.outputCh.send(packet)
                    }
                    IpNumber.TCP -> {
                        tcpVpnService!!.outputCh.send(packet)
                    }
                    else -> {
                        Log.w(TAG, "Unknown packet type");
                    }
                }
            }
        }

        whileSelect {
            inputCh.onReceive { value ->
                Log.d(TAG, "RESPONSE\n${value}")
                vpnOutputStream.write(ByteBuffer.wrap(value.rawData))
                true
            }
            closeCh.onReceiveOrNull {
                false
            }
        }
        vpnInputStream.close()
        vpnOutputStream.close()
        alive = false
        Log.i(TAG, "exit loop")
    }

    private fun stopVpn() {
        closeCh.close()
        vpnInterface?.close()
        udpVpnService?.stop()
        tcpVpnService?.stop()
        stopSelf()
        Log.i(TAG, "Stopped VPN")
    }

}
