package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.errors.NoNodesRegisteredException
import com.badoo.automation.deviceserver.ios.IDevice
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.AppInstaller
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory
import org.apache.commons.pool2.KeyedPooledObjectFactory
import org.apache.commons.pool2.PoolUtils
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.impl.DefaultPooledObject
import org.apache.commons.pool2.impl.GenericKeyedObjectPool
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig
import java.io.File
import java.net.URI
import java.net.URL
import java.time.Duration

class IDevicePooledFactory(
    private val nodeRegistry: NodeRegistry,
    private val deviceTimeoutInSecs: Duration,
    private val autoRegistrar: NodeRegistrar
) : BaseKeyedPooledObjectFactory<DesiredCapabilities, IDevice>() {

        override fun wrap(value: IDevice): PooledObject<IDevice> {
            return DefaultPooledObject<IDevice>(value)
        }

        override fun create(desiredCaps: DesiredCapabilities): IDevice {
            println("creating simulator for $desiredCaps")
            try {
                val dto = nodeRegistry.createDeviceAsync(desiredCaps, deviceTimeoutInSecs, "")
                val node = nodeRegistry.activeDevices.getNodeFor(dto.ref)

            } catch(e: NoNodesRegisteredException) {
                val erredNodes = autoRegistrar.nodeWrappers.filter { n -> n.lastError != null }
                val errors = erredNodes.joinToString { n -> "${n.node.remoteAddress} -> ${n.lastError?.localizedMessage}" }
                throw(NoNodesRegisteredException(e.message+"\n$errors"))
            }

            return object : IDevice {
                override fun prepareAsync() {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override val calabashPort: Int
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val deviceInfo: DeviceInfo
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val mjpegServerPort: Int
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val wdaEndpoint: URI
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val fbsimctlEndpoint: URI
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val udid: UDID
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val ref: DeviceRef
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val deviceState: DeviceState
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val userPorts: DeviceAllocatedPorts
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
                override val videoRecorder: VideoRecorder
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.

                override fun uninstallApplication(bundleId: String, appInstaller: AppInstaller) {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun status(): SimulatorStatusDTO {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override val lastException: Exception?
                    get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.

                override fun lastCrashLog(): CrashLog {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun endpointFor(port: Int): URL {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun release(reason: String) {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun installApplication(appInstaller: AppInstaller, appBundleId: String, appBinaryPath: File) {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun appInstallationStatus(): Map<String, Boolean> {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

            }
        }

        override fun destroyObject(key: DesiredCapabilities, p: PooledObject<IDevice>) {
            val ref = p.`object`.ref
            nodeRegistry.deleteReleaseDevice(ref, "Destroyed by pool")
            super.destroyObject(key, p)
        }
}

class PoolDeviceProvider(
    private val nodeConfigs: List<NodeConfig>,
    private val nodeRegistry: NodeRegistry,
    private val deviceTimeoutInSecs: Duration,
    private val autoRegistrar: NodeRegistrar
) {
    private val maxTotalDevices: Int by lazy {
        nodeConfigs.map {
            val connectedDevicesCount = it.knownDevices.size
            if (connectedDevicesCount > 0) {
                connectedDevicesCount
            } else {
                it.simulatorLimit
            }
        }.reduce { acc, i -> i + acc }
    }

    val poolConfig = GenericKeyedObjectPoolConfig<IDevice>().apply {
        maxTotal = maxTotalDevices
        maxTotalPerKey = maxTotalDevices
        maxIdlePerKey = maxTotalDevices
        minIdlePerKey = 0
        lifo = false
    }

    val simulatorFactory: KeyedPooledObjectFactory<DesiredCapabilities, IDevice> = IDevicePooledFactory(
        nodeRegistry,
        deviceTimeoutInSecs,
        autoRegistrar
    )
    val factory = PoolUtils.synchronizedKeyedPooledFactory(simulatorFactory)
    val pool = GenericKeyedObjectPool<DesiredCapabilities, IDevice>(factory, poolConfig)
}
