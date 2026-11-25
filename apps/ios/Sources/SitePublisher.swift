import Foundation
import CryptoKit
import Citadel
import NIOCore
import NIOSSH
@preconcurrency import FilesProvider

struct HostingDestination: Codable, Sendable {
    let `protocol`: String
    let host: String
    let port: Int
    let username: String
    let password: String
    let directory: String
    var _hostKeyFingerprint: String?
    var publicPathPrefix: String?
    var uploadSubfolder: String?
}

struct HostKeyApproval: Error, Identifiable, Sendable {
    let host: String
    let port: Int
    let fingerprint: String
    var id: String { "\(host):\(port)" }
}

private struct UntrustedHostKey: Error {}

private struct PublishingFailure: LocalizedError {
    let step: String
    let reason: String
    var errorDescription: String? {
        "Publishing failed \(step). \(reason) Existing files are overwritten; some files may already have been uploaded."
    }
}

private final class HostKeyCheck: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    let pin: String?
    private let lock = NSLock()
    private var observed: String?
    init(pin: String?) { self.pin = pin }
    var fingerprint: String? { lock.lock(); defer { lock.unlock() }; return observed }
    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        let encoded = String(openSSHPublicKey: hostKey).split(separator: " ")
        guard encoded.count >= 2, let data = Data(base64Encoded: String(encoded[1])) else {
            validationCompletePromise.fail(UntrustedHostKey()); return
        }
        let fingerprint = "SHA256:" + Data(SHA256.hash(data: data)).base64EncodedString().replacingOccurrences(of: "=", with: "")
        lock.lock(); observed = fingerprint; lock.unlock()
        if fingerprint == pin { validationCompletePromise.succeed(()) }
        else { validationCompletePromise.fail(UntrustedHostKey()) }
    }
}

enum SitePublisher {
    static func publish(_ destination: HostingDestination, root: URL) async throws {
        let uploadPrefix = try PublishingPaths.uploadPrefix(destination.uploadSubfolder)
        let manager = FileManager.default
        guard let enumerator = manager.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey]) else {
            throw ContentError.invalid("Cannot read the generated website.")
        }
        var files: [(String, URL)] = []
        for case let file as URL in enumerator.allObjects {
            if try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true {
                files.append((try WebsiteFiles.relativePath(file, in: root), file))
            }
        }
        files.sort { lhs, rhs in
            if lhs.0 == "index.html" { return false }
            if rhs.0 == "index.html" { return true }
            return lhs.0 < rhs.0
        }
        guard files.contains(where: { $0.0 == "index.html" }) else { throw ContentError.invalid("Homepage is missing.") }
        do {
            if destination.protocol == "sftp" { try await sftp(destination, files: files, uploadPrefix: uploadPrefix) }
            else { try await ftp(destination, files: files, uploadPrefix: uploadPrefix) }
        } catch let approval as HostKeyApproval { throw approval }
        catch let failure as PublishingFailure { throw ContentError.invalid(failure.localizedDescription) }
        catch { throw ContentError.invalid("Publishing failed. \(safeReason(error)) Check the destination, permissions, certificate or saved server key. Some files may already have been uploaded.") }
    }

    private static func safeReason(_ error: Error) -> String {
        if let ftp = error as? FileProviderFTPError {
            switch ftp.code {
            case 421: return "FTP reply 421: the server's connection limit was reached or its service is unavailable."
            case 530: return "FTP reply 530: login was refused."
            case 550: return "FTP reply 550: the path is unavailable or writing is not permitted."
            default: return "FTP server reply \(ftp.code)."
            }
        }
        if let url = error as? URLError { return URLError(url.code).localizedDescription }
        return "The server or connection refused the operation."
    }

    private static func operation(_ action: (@escaping (Error?) -> Void) -> Void) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            action { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
    }

    private static func ftp(_ destination: HostingDestination, files: [(String, URL)], uploadPrefix: String) async throws {
        guard ["ftp", "ftps"].contains(destination.protocol) else { throw ContentError.invalid("Unsupported protocol.") }
        var components = URLComponents()
        components.scheme = destination.protocol == "ftps" ? "ftpes" : "ftp"
        components.host = destination.host
        components.port = destination.port
        components.path = destination.directory.hasSuffix("/") ? destination.directory : destination.directory + "/"
        guard let url = components.url,
              let provider = FTPFileProvider(baseURL: url, mode: .passive,
                  credential: URLCredential(user: destination.username, password: destination.password, persistence: .forSession)) else {
            throw ContentError.invalid("Invalid FTP destination.")
        }
        provider.serverTrustPolicy = .performDefaultEvaluation(validateHost: true)
        provider.securedDataConnection = true
        var directories = Set<String>()
        for (relativePath, file) in files {
            let path = uploadPrefix + relativePath
            let parts = path.split(separator: "/").dropLast()
            for count in 1..<(parts.count + 1) {
                let directory = parts.prefix(count).joined(separator: "/")
                if directories.insert(directory).inserted {
                    do {
                        try await PublishingOperation.directory { done in
                            provider.create(folder: String(parts[count - 1]), at: parts.prefix(count - 1).joined(separator: "/"), completionHandler: done)
                        }
                    } catch {
                        do {
                            let isDirectory = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Bool, Error>) in
                                provider.attributesOfItem(path: directory) { item, error in
                                    if let error { continuation.resume(throwing: error) }
                                    else { continuation.resume(returning: item?.isDirectory == true) }
                                }
                            }
                            guard isDirectory else { throw error }
                        } catch {
                            throw PublishingFailure(step: "preparing folder \(directory)", reason: safeReason(error))
                        }
                    }
                }
            }
            do {
                try await operation { done in provider.copyItem(localFile: file, to: path, overwrite: true, completionHandler: done) }
            } catch { throw PublishingFailure(step: "uploading \(path)", reason: safeReason(error)) }
        }
    }

    private static func sftp(_ destination: HostingDestination, files: [(String, URL)], uploadPrefix: String) async throws {
        let validator = HostKeyCheck(pin: destination._hostKeyFingerprint)
        let settings = SSHClientSettings(host: destination.host, port: destination.port,
            authenticationMethod: { .passwordBased(username: destination.username, password: destination.password) },
            hostKeyValidator: .custom(validator))
        let client: SSHClient
        do { client = try await SSHClient.connect(to: settings) }
        catch {
            if destination._hostKeyFingerprint == nil, let fingerprint = validator.fingerprint {
                throw HostKeyApproval(host: destination.host, port: destination.port, fingerprint: fingerprint)
            }
            throw error
        }
        do {
            let sftp = try await client.openSFTP()
            do {
                var directories = Set<String>()
                for (relativePath, file) in files {
                    let path = uploadPrefix + relativePath
                    let prefix = destination.directory == "/" ? "" : destination.directory.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                    let remote = destination.directory.hasPrefix("/") ? "/" + [prefix, path].filter { !$0.isEmpty }.joined(separator: "/")
                        : [prefix, path].filter { !$0.isEmpty }.joined(separator: "/")
                    let parts = remote.split(separator: "/").dropLast()
                    for count in 1..<(parts.count + 1) {
                        let directory = (remote.hasPrefix("/") ? "/" : "") + parts.prefix(count).joined(separator: "/")
                        if directories.insert(directory).inserted { try? await sftp.createDirectory(atPath: directory) }
                    }
                    try await sftp.withFile(filePath: remote, flags: [.write, .create, .truncate]) { remoteFile in
                        let input = try FileHandle(forReadingFrom: file)
                        defer { try? input.close() }
                        var offset: UInt64 = 0
                        while let data = try input.read(upToCount: 64 * 1024), !data.isEmpty {
                            var buffer = ByteBufferAllocator().buffer(capacity: data.count)
                            buffer.writeBytes(data)
                            try await remoteFile.write(buffer, at: offset)
                            offset += UInt64(data.count)
                        }
                    }
                }
                try await sftp.close()
            } catch { try? await sftp.close(); throw error }
            try await client.close()
        } catch { try? await client.close(); throw error }
    }
}
