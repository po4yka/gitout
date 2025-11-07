package com.jakewharton.gitout

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer that accepts Telegram user IDs as either integers or strings.
 *
 * Users occasionally quote their IDs in TOML which makes them strings. The original
 * `List<Long>` serializer rejected those values resulting in a startup failure. This
 * serializer gracefully handles both numeric and string representations to keep the
 * configuration flexible while always exposing a `List<Long>` to the application.
 */
internal object TelegramUserIdListSerializer : KSerializer<List<Long>> {
        private val delegate = ListSerializer(Long.serializer())

        override val descriptor: SerialDescriptor = delegate.descriptor

        override fun deserialize(decoder: Decoder): List<Long> {
                val composite = decoder.beginStructure(descriptor)
                val results = mutableListOf<Long>()

                while (true) {
                        val index = composite.decodeElementIndex(descriptor)
                        if (index == CompositeDecoder.DECODE_DONE) {
                                break
                        }

                        val value = try {
                                composite.decodeLongElement(descriptor, index)
                        } catch (error: SerializationException) {
                                val rawValue = decodeAsString(composite, index, error)
                                rawValue.toLongOrNull() ?: throw SerializationException(
                                        "Invalid Telegram user id '$rawValue'. Expected a numeric value.",
                                        error,
                                )
                        }

                        results += value
                }

                composite.endStructure(descriptor)
                return results
        }

        override fun serialize(encoder: Encoder, value: List<Long>) {
                val composite = encoder.beginCollection(descriptor, value.size)
                value.forEachIndexed { index, element ->
                        composite.encodeLongElement(descriptor, index, element)
                }
                composite.endStructure(descriptor)
        }

        private fun decodeAsString(
                composite: CompositeDecoder,
                index: Int,
                original: SerializationException,
        ): String {
                return try {
                        composite.decodeStringElement(descriptor, index)
                } catch (_: SerializationException) {
                        throw original
                }
        }
}
