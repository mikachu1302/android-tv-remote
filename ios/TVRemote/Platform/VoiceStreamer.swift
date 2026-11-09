import AVFoundation
import Foundation

private let sampleRate: Double = 8_000
private let chunkBytes = 8 * 1024 // the TV's minimum chunk size

/// Streams the phone's microphone to the TV's voice search for as long as it runs. The microphone is only open
/// between `start` and `stop` (the user holds the mic button), and the audio goes only to the TV.
final class VoiceStreamer {
    private let connection: RemoteConnection
    private let session: Int
    private let engine = AVAudioEngine()
    private let queue = DispatchQueue(label: "tvremote.voice")
    private var pending = [UInt8]()
    private var running = false

    init(connection: RemoteConnection, session: Int) {
        self.connection = connection
        self.session = session
    }

    func start() throws {
        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.record, mode: .default)
        try audio.setActive(true)
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard let outputFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
            throw ConnectionError(message: "The microphone format is not supported")
        }
        running = true
        input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
            guard let self else { return }
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * sampleRate / inputFormat.sampleRate) + 64
            guard let out = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else { return }
            var supplied = false
            var error: NSError?
            converter.convert(to: out, error: &error) { _, status in
                if supplied {
                    status.pointee = .noDataNow
                    return nil
                }
                supplied = true
                status.pointee = .haveData
                return buffer
            }
            guard error == nil, let samples = out.int16ChannelData?[0] else { return }
            let bytes = Array(UnsafeBufferPointer(start: UnsafeRawPointer(samples).assumingMemoryBound(to: UInt8.self),
                                                  count: Int(out.frameLength) * 2))
            self.queue.async { self.append(bytes) }
        }
        engine.prepare()
        try engine.start()
    }

    private func append(_ bytes: [UInt8]) {
        guard running else { return }
        pending.append(contentsOf: bytes)
        while pending.count >= chunkBytes {
            let chunk = Array(pending.prefix(chunkBytes))
            pending.removeFirst(chunkBytes)
            try? connection.voiceChunk(session, samples: chunk)
        }
    }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        queue.async {
            self.running = false
            if !self.pending.isEmpty { // pad the last piece up to the minimum size with silence
                self.pending.append(contentsOf: [UInt8](repeating: 0, count: chunkBytes - self.pending.count))
                try? self.connection.voiceChunk(self.session, samples: self.pending)
                self.pending = []
            }
            try? self.connection.voiceEnd(self.session)
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
