package com.kbbench.algorithm.base

/** Stable description of the optional denoiser at the fixed preprocessing boundary. */
data class DenoiserDescriptor(
    val id: String,
    val name: String,
    val acceptedDomains: Set<FrameDomain> = FrameDomain.entries.toSet(),
    val acceptedStorage: Set<FrameStorage> = FrameStorage.entries.toSet(),
    val outputDomain: FrameDomain? = null,
    val outputStorage: FrameStorage = FrameStorage.UNCLIPPED_FLOAT,
    val parameters: List<AlgorithmParameter> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Denoiser id must not be blank" }
        require(name.isNotBlank()) { "Denoiser name must not be blank" }
        require(acceptedDomains.isNotEmpty()) { "Denoiser '$id' must accept at least one domain" }
        require(acceptedStorage.isNotEmpty()) { "Denoiser '$id' must accept at least one storage mode" }
    }
}

/** Pure Kotlin contract for the one optional denoiser supported by the fixed preprocessing flow. */
interface Denoiser {
    val descriptor: DenoiserDescriptor

    /** Effective constructor values for this configured denoiser instance. */
    val effectiveParameters: Map<String, Double>
        get() = descriptor.parameters.effectiveValues(emptyMap())

    fun process(input: Frame): Frame

    fun withParameters(values: Map<String, Double>): Denoiser = this
}

data class DenoiserExecution(
    val denoiserId: String,
    val output: Frame,
    val effectiveParameters: Map<String, Double>,
    val elapsedMs: Long,
)

/** Validates and times one denoiser without retaining intermediate pipeline outputs. */
object DenoiserRunner {
    fun run(
        input: Frame,
        denoiser: Denoiser,
        parameterValues: Map<String, Double> = emptyMap(),
    ): DenoiserExecution {
        val configured = denoiser.withParameters(parameterValues)
        val descriptor = configured.descriptor
        require(input.domain in descriptor.acceptedDomains) {
            "Denoiser '${descriptor.id}' does not accept ${input.domain} input"
        }
        require(input.storage in descriptor.acceptedStorage) {
            "Denoiser '${descriptor.id}' does not accept ${input.storage} input"
        }

        val started = System.nanoTime()
        val output = configured.process(input)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        require(output.width == input.width && output.height == input.height) {
            "Denoiser '${descriptor.id}' changed geometry from ${input.width}x${input.height} " +
                "to ${output.width}x${output.height}"
        }
        descriptor.outputDomain?.let { expected ->
            require(output.domain == expected) {
                "Denoiser '${descriptor.id}' produced ${output.domain}, expected $expected"
            }
        } ?: require(output.domain == input.domain) {
            "Denoiser '${descriptor.id}' must preserve input domain ${input.domain}"
        }
        require(output.storage == descriptor.outputStorage) {
            "Denoiser '${descriptor.id}' produced ${output.storage}, expected ${descriptor.outputStorage}"
        }
        return DenoiserExecution(
            denoiserId = descriptor.id,
            output = output,
            effectiveParameters = configured.effectiveParameters,
            elapsedMs = elapsedMs,
        )
    }
}
