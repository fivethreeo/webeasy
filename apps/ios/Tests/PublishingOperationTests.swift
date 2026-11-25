import Foundation

@main
struct PublishingOperationTests {
    enum Failure: Error { case denied }

    static func main() async throws {
        let success = Progress(totalUnitCount: 1)
        let successClosed = DispatchSemaphore(value: 0)
        success.cancellationHandler = { successClosed.signal() }
        try await PublishingOperation.directory { done in
            success.completedUnitCount = 1
            done(nil)
            return success
        }
        precondition(success.isCancelled, "A completed MKD connection must be closed.")
        precondition(successClosed.wait(timeout: .now() + 1) == .success, "Closing must invoke the stream cancellation handler.")

        let failure = Progress(totalUnitCount: 1)
        let failureClosed = DispatchSemaphore(value: 0)
        failure.cancellationHandler = { failureClosed.signal() }
        do {
            try await PublishingOperation.directory { done in
                done(Failure.denied)
                return failure
            }
            preconditionFailure("The directory error must reach the caller.")
        } catch Failure.denied {}
        precondition(failure.isCancelled, "A failed MKD connection must also be closed.")
        precondition(failureClosed.wait(timeout: .now() + 1) == .success)

        let asynchronous = Progress(totalUnitCount: 1)
        let asynchronousClosed = DispatchSemaphore(value: 0)
        asynchronous.cancellationHandler = { asynchronousClosed.signal() }
        try await PublishingOperation.directory { done in
            DispatchQueue.global().async {
                asynchronous.completedUnitCount = 1
                done(nil)
            }
            return asynchronous
        }
        precondition(asynchronous.isCancelled, "An asynchronous completion must close its connection.")
        precondition(asynchronousClosed.wait(timeout: .now() + 1) == .success)
        print("Publishing operation tests passed.")
    }
}
