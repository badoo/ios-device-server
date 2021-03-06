package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.NodeConfig
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future

class NodeRegistrar(
    nodesConfig: Set<NodeConfig>,
    nodeFactory: IHostFactory,
    private val nodeRegistry: NodeRegistry,
    private val registrationInterval: Duration = DEFAULT_REGISTRATION_INTERVAL
) {
    companion object {
        val DEFAULT_REGISTRATION_INTERVAL: Duration = Duration.ofSeconds(60)
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private var autoRegisteringJob: Future<*>? = null
    @Volatile
    private var restartingJob: Future<*>? = null
    private val nodeRestarter = NodeRestarter(nodeRegistry)

    val nodeWrappers: List<NodeWrapper> = nodesConfig.map {
        NodeWrapper(it, nodeFactory, nodeRegistry)
    }

    fun startAutoRegistering() {
        val executor = Executors.newSingleThreadExecutor()
        autoRegisteringJob = executor.submit({
            while (!Thread.currentThread().isInterrupted) {
                autoRegister()
                Thread.sleep(registrationInterval.toMillis())
            }
        })
        executor.shutdown()
    }

    fun stop() {
        //FIXME: Do proper clean up on server exit
        autoRegisteringJob?.cancel(true)
        autoRegisteringJob = null
    }

    private fun autoRegister() {
        val unregistered = nodeWrappers - nodeRegistry.getAll()

        if (unregistered.isEmpty()) {
            return
        }

        logger.debug("Going to auto register ${unregistered.map(NodeWrapper::toString)}")
        val executor = Executors.newFixedThreadPool(unregistered.size)
        val results: List<Future<*>> = unregistered.map { nodeWrapper ->
            executor.submit {
                nodeWrapper.stop()
                if (nodeWrapper.start()) {
                    nodeRegistry.add(nodeWrapper)
                    nodeWrapper.enable()
                    nodeWrapper.startPeriodicHealthCheck()
                }
            }
        }
        executor.shutdown()
        results.forEach { result ->
            try {
                result.get()
            } catch (e: Throwable) {
                logger.error("Error while starting node", e)
            }
        }
        nodeRegistry.setInitialRegistrationComplete()
    }

    @Synchronized
    fun restartNodesGracefully(isParallel: Boolean): Boolean {
        val job = restartingJob

        if (job == null || job.isDone) {
            val executor = Executors.newSingleThreadExecutor()
            restartingJob = executor.submit {
                nodeRestarter.restartNodeWrappers(
                    nodeRegistry.getAll(),
                    isParallel
                )
            }
            executor.shutdown()

            return true
        } else {
            return false
        }
    }
}
