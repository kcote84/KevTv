package ca.kevtv

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StereoRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val mix = ChannelMixingAudioProcessor().apply {
            for (channels in 1..8) putChannelMixingMatrix(ChannelMixingMatrix(channels, 2, StereoMix.coefficients(channels)))
        }
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false) // Use the 16-bit PCM processor chain.
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(mix))
            .build()
        // The physical device may advertise HDMI passthrough. Disable every compressed
        // direct path in stereo mode while leaving its decoded PCM path available.
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW && (format.channelCount == Format.NO_VALUE || format.channelCount in 1..8)) super.getFormatSupport(format)
                else AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
    }
}
