package dobra.app.ai

import android.content.Context
import android.util.Log
import dobra.ai.ask.AskService
import dobra.ai.ask.ConversationStore
import dobra.ai.provider.AiGateway
import dobra.ai.provider.MemoryBlobStorage
import dobra.ai.provider.HttpTransport
import dobra.ai.provider.LoggingTransport
import dobra.ai.provider.ProviderRegistry
import dobra.ai.provider.UsageLedger
import java.io.File

/** What the providers screen and the AI jobs share: the registry, the usage ledger and the gateway. */
class AiSetup(
    val registry: ProviderRegistry,
    val ledger: UsageLedger,
    val gateway: AiGateway,
    /** The conversations of the stories ("Perguntar à IA"). */
    val conversations: ConversationStore = ConversationStore(MemoryBlobStorage()),
) {
    /** Asks the answering model about one story. */
    val ask: AskService = AskService(gateway, ledger, conversations)

    companion object {
        /** Files live in `files/ai`, apart from the store; keys are sealed by a key of the system's keystore. */
        fun create(context: Context, transport: HttpTransport = UrlConnectionTransport()): AiSetup {
            val storage = FileBlobStorage(File(context.filesDir, "ai"))
            val cipher = AesGcmCipher { keystoreKey() }
            val registry = ProviderRegistry(storage, cipher)
            val ledger = UsageLedger(storage)
            val logged = LoggingTransport(transport) { Log.i("dobra-ai", it) }
            return AiSetup(
                registry, ledger, AiGateway(registry, logged, ledger, System::currentTimeMillis),
                ConversationStore(storage),
            )
        }
    }
}
