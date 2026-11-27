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
    var directory: String
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
    private static let templateLimit = 256 * 1024

    static func privateBackupDestination(_ destination: HostingDestination, directory: String) throws -> HostingDestination {
        var result = destination
        result.directory = try PublishingPaths.privateBackupDirectory(directory, website: destination.directory, subfolder: destination.uploadSubfolder)
        result.uploadSubfolder = ""
        return result
    }

    static func withDirectory<T: Sendable>(_ destination: HostingDestination, create: Bool = false,
        action: @Sendable (HostingDirectory) async throws -> T) async throws -> T {
        let prefix = try PublishingPaths.uploadPrefix(destination.uploadSubfolder)
        let identity = SHA256.hash(data: Data([
            destination.protocol, destination.host, String(destination.port), destination.username, destination.directory, prefix
        ].joined(separator: "\n").utf8)).map { String(format: "%02x", $0) }.joined()
        func scoped(_ directory: HostingDirectory) async throws -> T {
            if !prefix.isEmpty {
                if create { try await directory.ensureDirectory(String(prefix.dropLast())) }
                else {
                    let parts = prefix.split(separator: "/")
                    var parent = ""
                    for part in parts {
                        guard try await directory.entries(parent).contains(where: { $0.name == String(part) && $0.directory }) else {
                            throw ContentError.invalid("Hosting upload subfolder is unavailable.")
                        }
                        parent += (parent.isEmpty ? "" : "/") + part
                    }
                }
            }
            let folder = HostingDirectory(identity: identity,
                list: { try await directory.list(prefix + $0) },
                mkdir: { try await directory.mkdir(prefix + $0) },
                download: { try await directory.download(prefix + $0, $1, $2) },
                upload: { try await directory.upload($0, prefix + $1) },
                remove: { try await directory.remove(prefix + $0, $1) })
            return try await action(folder)
        }
        do {
            if destination.protocol == "sftp" {
                let client = try await sftpClient(destination)
                do {
                    let sftp = try await client.openSFTP()
                    do {
                        let directory = HostingDirectory(identity: identity,
                            list: { path in
                                let listings = try await sftp.listDirectory(atPath: remotePath(destination, path))
                                return listings.flatMap(\.components).map { file in
                                    let type = (file.attributes.permissions ?? 0) & 0o170000
                                    return HostingDirectory.File(name: file.filename, directory: type == 0o040000,
                                        regular: type == 0o100000, size: file.attributes.size.flatMap { Int64(exactly: $0) } ?? -1)
                                }
                            },
                            mkdir: { try await sftp.createDirectory(atPath: remotePath(destination, $0)) },
                            download: { path, local, maximum in
                                try await sftp.withFile(filePath: remotePath(destination, path), flags: [.read]) { file in
                                    guard FileManager.default.createFile(atPath: local.path, contents: nil) else {
                                        throw ContentError.invalid("Cannot stage a hosting download.")
                                    }
                                    let output = try FileHandle(forWritingTo: local)
                                    defer { try? output.close() }
                                    var count: Int64 = 0
                                    while true {
                                        let length = count == maximum ? 1 : Int(min(65_536, maximum - count))
                                        let buffer = try await file.read(from: UInt64(count), length: UInt32(length))
                                        guard buffer.readableBytes > 0 else { break }
                                        guard Int64(buffer.readableBytes) <= maximum - count else {
                                            throw ContentError.invalid("Hosting file exceeds its permitted size.")
                                        }
                                        try output.write(contentsOf: Data(buffer.readableBytesView))
                                        count += Int64(buffer.readableBytes)
                                    }
                                    try output.synchronize()
                                }
                            },
                            upload: { local, path in
                                try await sftp.withFile(filePath: remotePath(destination, path), flags: [.write, .create, .truncate]) { file in
                                    let input = try FileHandle(forReadingFrom: local)
                                    defer { try? input.close() }
                                    var offset: UInt64 = 0
                                    while let data = try input.read(upToCount: 65_536), !data.isEmpty {
                                        var buffer = ByteBufferAllocator().buffer(capacity: data.count)
                                        buffer.writeBytes(data)
                                        try await file.write(buffer, at: offset)
                                        offset += UInt64(data.count)
                                    }
                                }
                            },
                            remove: { path, directory in
                                if directory { try await sftp.rmdir(at: remotePath(destination, path)) }
                                else { try await sftp.remove(at: remotePath(destination, path)) }
                            })
                        let result = try await scoped(directory)
                        try await sftp.close()
                        try await client.close()
                        return result
                    } catch { try? await sftp.close(); throw error }
                } catch { try? await client.close(); throw error }
            }
            // FilesProvider 0.26 applies its base path twice during directory listings.
            // Keep that base at "/" and scope every operation explicitly instead.
            var ftpRoot = destination
            ftpRoot.directory = "/"
            let provider = try ftpProvider(ftpRoot)
            let directory = HostingDirectory(identity: identity,
                list: { path in
                    try await withCheckedThrowingContinuation { continuation in
                        provider.contentsOfDirectory(path: remotePath(destination, path)) { files, error in
                            if let error { continuation.resume(throwing: error) }
                            else {
                                continuation.resume(returning: files.map {
                                    HostingDirectory.File(name: $0.name, directory: $0.isDirectory && !$0.isSymLink,
                                        regular: $0.isRegularFile && !$0.isSymLink, size: $0.size)
                                })
                            }
                        }
                    }
                },
                mkdir: { path in
                    let parts = path.split(separator: "/")
                    try await PublishingOperation.directory { done in
                        provider.create(folder: String(parts.last!), at: remotePath(destination, parts.dropLast().joined(separator: "/")), completionHandler: done)
                    }
                },
                download: { path, local, maximum in
                    guard FileManager.default.createFile(atPath: local.path, contents: nil) else {
                        throw ContentError.invalid("Cannot stage a hosting download.")
                    }
                    let output = try FileHandle(forWritingTo: local)
                    defer { try? output.close() }
                    var count: Int64 = 0
                    while true {
                        let length = count == maximum ? 1 : Int(min(1024 * 1024, maximum - count))
                        let bytes = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                            provider.contents(path: remotePath(destination, path), offset: count, length: length) { data, error in
                                if let error { continuation.resume(throwing: error) }
                                else if let data { continuation.resume(returning: data) }
                                else { continuation.resume(throwing: ContentError.invalid("Cannot read hosting file.")) }
                            }
                        }
                        if bytes.isEmpty { break }
                        guard Int64(bytes.count) <= maximum - count else { throw ContentError.invalid("Hosting file exceeds its permitted size.") }
                        try output.write(contentsOf: bytes)
                        count += Int64(bytes.count)
                        if bytes.count < length { break }
                    }
                    try output.synchronize()
                },
                upload: { local, path in
                    try await operation { provider.copyItem(localFile: local, to: remotePath(destination, path), overwrite: true, completionHandler: $0) }
                },
                remove: { path, _ in
                    try await operation { provider.removeItem(path: remotePath(destination, path), completionHandler: $0) }
                })
            return try await scoped(directory)
        } catch let approval as HostKeyApproval { throw approval }
        catch let error as ContentError { throw error }
        catch {
            let detail = error as NSError
            throw ContentError.invalid("Hosting directory operation failed (\(detail.domain), code \(detail.code)). Check the destination, permissions, certificate or saved server key.")
        }
    }

    static func loadTemplates(_ destination: HostingDestination, paths: [String]) async throws -> [String: String] {
        guard !paths.isEmpty, paths.allSatisfy({
            $0.range(of: "^webeasy_templates/(templates|components)/[a-z][a-z0-9-]*\\.html$", options: .regularExpression) != nil
        }) else { throw ContentError.invalid("Invalid template paths.") }
        let prefix = try PublishingPaths.uploadPrefix(destination.uploadSubfolder)
        do {
            var sources: [String: String] = [:]
            if destination.protocol == "sftp" {
                let client = try await sftpClient(destination)
                do {
                    let sftp = try await client.openSFTP()
                    do {
                        for path in paths {
                            let data = try await sftp.withFile(filePath: remotePath(destination, prefix + path), flags: [.read]) { file in
                                var data = Data()
                                while true {
                                    let buffer = try await file.read(from: UInt64(data.count), length: 8192)
                                    if buffer.readableBytes == 0 { break }
                                    guard data.count + buffer.readableBytes <= templateLimit else {
                                        throw ContentError.invalid("Template is too large.")
                                    }
                                    data.append(contentsOf: buffer.readableBytesView)
                                }
                                return data
                            }
                            guard let source = String(data: data, encoding: .utf8) else { throw ContentError.invalid("Templates must use UTF-8.") }
                            sources[path] = source
                        }
                        try await sftp.close()
                    } catch { try? await sftp.close(); throw error }
                    try await client.close()
                } catch { try? await client.close(); throw error }
            } else {
                let provider = try ftpProvider(destination)
                for path in paths {
                    let data = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                        provider.contents(path: prefix + path, offset: 0, length: templateLimit + 1) { data, error in
                            if let error { continuation.resume(throwing: error) }
                            else if let data { continuation.resume(returning: data) }
                            else { continuation.resume(throwing: ContentError.invalid("Cannot read template.")) }
                        }
                    }
                    guard data.count <= templateLimit, let source = String(data: data, encoding: .utf8) else {
                        throw ContentError.invalid("Template is oversized or not UTF-8.")
                    }
                    sources[path] = source
                }
            }
            return sources
        } catch let approval as HostKeyApproval { throw approval }
        catch {
            throw ContentError.invalid("Cannot load templates. Explicitly export templates and assets to hosting from Advanced settings first. Check read permissions, certificate or saved server key. Local templates have not changed.")
        }
    }

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

    private static func ftpProvider(_ destination: HostingDestination) throws -> FTPFileProvider {
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
        return provider
    }

    private static func ftp(_ destination: HostingDestination, files: [(String, URL)], uploadPrefix: String) async throws {
        let provider = try ftpProvider(destination)
        var directories = Set<String>()
        var templateListings: [String: Set<String>] = [:]
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
                if relativePath.hasPrefix("webeasy_templates/") {
                    let parent = parts.joined(separator: "/")
                    if templateListings[parent] == nil {
                        templateListings[parent] = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Set<String>, Error>) in
                            provider.contentsOfDirectory(path: parent) { files, error in
                                if let error { continuation.resume(throwing: error) }
                                else { continuation.resume(returning: Set(files.map(\.name))) }
                            }
                        }
                    }
                    if templateListings[parent]?.contains(file.lastPathComponent) == true { continue }
                }
                try await operation { done in provider.copyItem(localFile: file, to: path, overwrite: true, completionHandler: done) }
            } catch { throw PublishingFailure(step: "uploading \(path)", reason: safeReason(error)) }
        }
    }

    private static func remotePath(_ destination: HostingDestination, _ path: String) -> String {
        let prefix = destination.directory.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return (destination.directory.hasPrefix("/") ? "/" : "") + [prefix, path].filter { !$0.isEmpty }.joined(separator: "/")
    }

    private static func sftpClient(_ destination: HostingDestination) async throws -> SSHClient {
        let validator = HostKeyCheck(pin: destination._hostKeyFingerprint)
        let settings = SSHClientSettings(host: destination.host, port: destination.port,
            authenticationMethod: { .passwordBased(username: destination.username, password: destination.password) },
            hostKeyValidator: .custom(validator))
        do { return try await SSHClient.connect(to: settings) }
        catch {
            if destination._hostKeyFingerprint == nil, let fingerprint = validator.fingerprint {
                throw HostKeyApproval(host: destination.host, port: destination.port, fingerprint: fingerprint)
            }
            throw error
        }
    }

    private static func sftp(_ destination: HostingDestination, files: [(String, URL)], uploadPrefix: String) async throws {
        let client = try await sftpClient(destination)
        do {
            let sftp = try await client.openSFTP()
            do {
                var directories = Set<String>()
                for (relativePath, file) in files {
                    let path = uploadPrefix + relativePath
                    let remote = remotePath(destination, path)
                    let parts = remote.split(separator: "/").dropLast()
                    for count in 1..<(parts.count + 1) {
                        let directory = (remote.hasPrefix("/") ? "/" : "") + parts.prefix(count).joined(separator: "/")
                        if directories.insert(directory).inserted { try? await sftp.createDirectory(atPath: directory) }
                    }
                    let preserve = relativePath.hasPrefix("webeasy_templates/")
                    if preserve {
                        do { _ = try await sftp.getAttributes(at: remote); continue }
                        catch let status as SFTPMessage.Status where status.errorCode == .noSuchFile {}
                    }
                    try await sftp.withFile(filePath: remote, flags: preserve ? [.write, .create, .forceCreate] : [.write, .create, .truncate]) { remoteFile in
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
