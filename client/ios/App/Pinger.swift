import Foundation
import Network

/// TCP-connect latency probe (mirrors the Android TCP ping). WireGuard is UDP/local, so
/// callers skip it.
enum Pinger {
    static func tcp(host rawHost: String, port: Int, timeout: TimeInterval = 3) async -> Int {
        let host = rawHost.split(separator: "/").first.map(String.init) ?? rawHost
        guard !host.isEmpty, let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return -1 }

        return await withCheckedContinuation { cont in
            let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
            let start = Date()
            var finished = false
            func finish(_ ms: Int) {
                guard !finished else { return }
                finished = true
                conn.cancel()
                cont.resume(returning: ms)
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(Int(Date().timeIntervalSince(start) * 1000))
                case .failed, .cancelled: finish(-1)
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { finish(-1) }
        }
    }
}
