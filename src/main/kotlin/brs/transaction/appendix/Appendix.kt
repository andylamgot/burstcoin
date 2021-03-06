package brs.transaction.appendix

import brs.api.grpc.proto.BrsApi
import brs.api.grpc.service.ProtoBuilder
import brs.api.grpc.service.toByteString
import brs.entity.Account
import brs.entity.DependencyProvider
import brs.entity.Transaction
import brs.objects.Constants
import brs.objects.FluxValues
import brs.util.BurstException
import brs.util.convert.*
import brs.util.crypto.Crypto
import brs.util.json.getMemberAsByte
import brs.util.json.mustGetMemberAsBoolean
import brs.util.json.mustGetMemberAsJsonObject
import brs.util.json.mustGetMemberAsString
import burst.kit.entity.BurstEncryptedMessage
import com.google.gson.JsonObject
import com.google.protobuf.Any
import java.nio.ByteBuffer

interface Appendix {
    val size: Int
    val jsonObject: JsonObject
    val version: Byte
    val protobufMessage: Any
    fun putBytes(buffer: ByteBuffer)
    fun verifyVersion(transactionVersion: Byte): Boolean
    fun validate(transaction: Transaction)
    fun preValidate(transaction: Transaction, height: Int)
    fun apply(transaction: Transaction, senderAccount: Account, recipientAccount: Account)

    abstract class AbstractAppendix : Appendix {
        final override val version: Byte

        // Must be function as it is needed in constructor and properties will not have been initialized
        internal abstract fun getAppendixName(): String

        override val size: Int
            get() = mySize + if (version > 0) 1 else 0

        internal abstract val mySize: Int

        override val jsonObject: JsonObject
            get() {
                val json = JsonObject()
                if (version > 0) {
                    json.addProperty("version.${getAppendixName()}", version)
                }
                putMyJSON(json)
                return json
            }

        internal constructor(attachmentData: JsonObject) {
            version = attachmentData.getMemberAsByte("version.${getAppendixName()}") ?: 0
        }

        internal constructor(buffer: ByteBuffer, transactionVersion: Byte) {
            version = if (transactionVersion.toInt() == 0) 0 else buffer.get()
        }

        internal constructor(version: Byte) {
            this.version = version
        }

        internal constructor(dp: DependencyProvider, blockchainHeight: Int) {
            this.version = (if (dp.fluxCapacitorService.getValue(
                    FluxValues.DIGITAL_GOODS_STORE,
                    blockchainHeight
                )
            ) 1 else 0).toByte()
        }

        override fun putBytes(buffer: ByteBuffer) {
            if (version > 0) {
                buffer.put(version)
            }
            putMyBytes(buffer)
        }

        internal abstract fun putMyBytes(buffer: ByteBuffer)

        internal abstract fun putMyJSON(attachment: JsonObject)

        override fun verifyVersion(transactionVersion: Byte): Boolean {
            return if (transactionVersion.toInt() == 0) version.toInt() == 0 else version > 0
        }
    }

    class Message : AbstractAppendix {
        val messageBytes: ByteArray
        val isText: Boolean

        override fun getAppendixName(): String = "Message"

        override val mySize: Int
            get() = 4 + messageBytes.size

        override val protobufMessage: Any
            get() = Any.pack(
                BrsApi.MessageAppendix.newBuilder()
                    .setVersion(super.version.toInt())
                    .setMessage(messageBytes.toByteString())
                    .setIsText(isText)
                    .build()
            )

        constructor(buffer: ByteBuffer, transactionVersion: Byte) : super(buffer, transactionVersion) {
            var messageLength = buffer.int
            this.isText = messageLength < 0
            if (messageLength < 0) {
                messageLength = messageLength and Integer.MAX_VALUE
            }
            if (messageLength > Constants.MAX_ARBITRARY_MESSAGE_LENGTH) {
                throw BurstException.NotValidException("Invalid arbitrary message length: $messageLength")
            }
            this.messageBytes = ByteArray(messageLength)
            buffer.get(this.messageBytes)
        }

        internal constructor(attachmentData: JsonObject) : super(attachmentData) {
            val messageString = attachmentData.mustGetMemberAsString("message")
            this.isText = attachmentData.mustGetMemberAsBoolean("messageIsText")
            this.messageBytes = if (isText) messageString.toBytes() else messageString.parseHexString()
        }

        constructor(dp: DependencyProvider, message: ByteArray, blockchainHeight: Int) : super(dp, blockchainHeight) {
            this.messageBytes = message
            this.isText = false
        }

        constructor(dp: DependencyProvider, string: String, blockchainHeight: Int) : super(dp, blockchainHeight) {
            this.messageBytes = string.toBytes()
            this.isText = true
        }

        constructor(dp: DependencyProvider, messageAppendix: BrsApi.MessageAppendix, blockchainHeight: Int) : super(
            dp,
            blockchainHeight
        ) {
            this.messageBytes = messageAppendix.message.toByteArray()
            this.isText = messageAppendix.isText
        }

        override fun putMyBytes(buffer: ByteBuffer) {
            buffer.putInt(if (isText) messageBytes.size or Integer.MIN_VALUE else messageBytes.size)
            buffer.put(messageBytes)
        }

        override fun putMyJSON(attachment: JsonObject) {
            attachment.addProperty("message", if (isText) messageBytes.toUtf8String() else messageBytes.toHexString())
            attachment.addProperty("messageIsText", isText)
        }

        override fun preValidate(transaction: Transaction, height: Int) {
            if (this.isText && transaction.version.toInt() == 0) {
                throw BurstException.NotValidException("Text messages not yet enabled")
            }
            if (transaction.version.toInt() == 0 && transaction.attachment !is Attachment.ArbitraryMessage) {
                throw BurstException.NotValidException("Message attachments not enabled for version 0 transactions")
            }
            if (messageBytes.size > Constants.MAX_ARBITRARY_MESSAGE_LENGTH) {
                throw BurstException.NotValidException("Invalid arbitrary message length: " + messageBytes.size)
            }
        }

        override fun validate(transaction: Transaction) {
            // Nothing to validate.
        }

        override fun apply(transaction: Transaction, senderAccount: Account, recipientAccount: Account) {
            // Do nothing by default
        }

        companion object {
            fun parse(attachmentData: JsonObject): Message? {
                return if (attachmentData.get("message") == null) {
                    null
                } else Message(attachmentData)
            }
        }
    }

    abstract class AbstractEncryptedMessage : AbstractAppendix {
        val encryptedData: BurstEncryptedMessage

        override val mySize: Int
            get() = 4 + encryptedData.size

        override val protobufMessage: Any
            get() = Any.pack(
                BrsApi.EncryptedMessageAppendix.newBuilder()
                    .setVersion(super.version.toInt())
                    .setEncryptedData(ProtoBuilder.buildEncryptedData(encryptedData))
                    .setType(type)
                    .build()
            )

        protected abstract val type: BrsApi.EncryptedMessageAppendix.Type

        constructor(buffer: ByteBuffer, transactionVersion: Byte) : super(buffer, transactionVersion) {
            var length = buffer.int
            val isText = length < 0
            if (length < 0) {
                length = length and Integer.MAX_VALUE
            }
            this.encryptedData = Crypto.readEncryptedData(
                buffer, length,
                Constants.MAX_ENCRYPTED_MESSAGE_LENGTH,
                isText
            )
        }

        constructor(attachmentJSON: JsonObject, encryptedMessageJSON: JsonObject) : super(attachmentJSON) {
            val data = encryptedMessageJSON.mustGetMemberAsString("data").parseHexString()
            val nonce = encryptedMessageJSON.mustGetMemberAsString("nonce").parseHexString()
            this.encryptedData = BurstEncryptedMessage(data, nonce, encryptedMessageJSON.mustGetMemberAsBoolean("isText"))
        }

        constructor(
            dp: DependencyProvider,
            encryptedData: BurstEncryptedMessage,
            blockchainHeight: Int
        ) : super(dp, blockchainHeight) {
            this.encryptedData = encryptedData
        }

        constructor(
            dp: DependencyProvider,
            encryptedMessageAppendix: BrsApi.EncryptedMessageAppendix,
            blockchainHeight: Int
        ) : super(dp, blockchainHeight) {
            this.encryptedData = ProtoBuilder.parseEncryptedData(encryptedMessageAppendix.encryptedData, encryptedMessageAppendix.isText)
        }

        override fun putMyBytes(buffer: ByteBuffer) {
            buffer.putInt(if (encryptedData.isText) encryptedData.data.size or Integer.MIN_VALUE else encryptedData.data.size)
            buffer.put(encryptedData.data)
            buffer.put(encryptedData.nonce)
        }

        override fun putMyJSON(attachment: JsonObject) {
            attachment.addProperty("data", encryptedData.data.toHexString())
            attachment.addProperty("nonce", encryptedData.nonce.toHexString())
            attachment.addProperty("isText", encryptedData.isText)
        }

        override fun preValidate(transaction: Transaction, height: Int) {
            if (encryptedData.data.size > Constants.MAX_ENCRYPTED_MESSAGE_LENGTH) {
                throw BurstException.NotValidException("Max encrypted message length exceeded")
            }
            if (encryptedData.nonce.size != 32 && encryptedData.data.isNotEmpty() || encryptedData.nonce.isNotEmpty() && encryptedData.data.isEmpty()) {
                throw BurstException.NotValidException("Invalid nonce length " + encryptedData.nonce.size)
            }
            if (transaction.version.toInt() == 0) {
                throw BurstException.NotValidException("Encrypted message attachments not enabled for version 0 transactions")
            }
        }

        override fun validate(transaction: Transaction) {
            // Nothing to validate.
        }

        override fun apply(transaction: Transaction, senderAccount: Account, recipientAccount: Account) {
        }
    }

    class EncryptedMessage : AbstractEncryptedMessage {
        override fun getAppendixName(): String = "EncryptedMessage"

        override val type: BrsApi.EncryptedMessageAppendix.Type
            get() = BrsApi.EncryptedMessageAppendix.Type.TO_RECIPIENT

        constructor(buffer: ByteBuffer, transactionVersion: Byte) : super(buffer, transactionVersion)

        internal constructor(attachmentData: JsonObject) : super(
            attachmentData,
            attachmentData.mustGetMemberAsJsonObject("encryptedMessage")
        )

        constructor(
            dp: DependencyProvider,
            encryptedData: BurstEncryptedMessage,
            blockchainHeight: Int
        ) : super(dp, encryptedData, blockchainHeight)

        constructor(
            dp: DependencyProvider,
            encryptedMessageAppendix: BrsApi.EncryptedMessageAppendix,
            blockchainHeight: Int
        ) : super(dp, encryptedMessageAppendix, blockchainHeight) {
            require(encryptedMessageAppendix.type == BrsApi.EncryptedMessageAppendix.Type.TO_RECIPIENT)
        }

        override fun putMyJSON(attachment: JsonObject) {
            val encryptedMessageJSON = JsonObject()
            super.putMyJSON(encryptedMessageJSON)
            attachment.add("encryptedMessage", encryptedMessageJSON)
        }

        override fun preValidate(transaction: Transaction, height: Int) {
            super.preValidate(transaction, height)
            if (!transaction.type.hasRecipient()) {
                throw BurstException.NotValidException("Encrypted messages cannot be attached to transactions with no recipient")
            }
        }

        companion object {
            fun parse(attachmentData: JsonObject): EncryptedMessage? {
                return if (attachmentData.get("encryptedMessage") == null) {
                    null
                } else EncryptedMessage(attachmentData)
            }
        }
    }

    class EncryptToSelfMessage : AbstractEncryptedMessage {
        override fun getAppendixName(): String = "EncryptToSelfMessage"

        override val type: BrsApi.EncryptedMessageAppendix.Type
            get() = BrsApi.EncryptedMessageAppendix.Type.TO_SELF

        constructor(buffer: ByteBuffer, transactionVersion: Byte) : super(buffer, transactionVersion)

        internal constructor(attachmentData: JsonObject) : super(
            attachmentData,
            attachmentData.mustGetMemberAsJsonObject("encryptToSelfMessage")
        )

        constructor(
            dp: DependencyProvider,
            encryptedData: BurstEncryptedMessage,
            blockchainHeight: Int
        ) : super(dp, encryptedData, blockchainHeight)

        constructor(
            dp: DependencyProvider,
            encryptedMessageAppendix: BrsApi.EncryptedMessageAppendix,
            blockchainHeight: Int
        ) : super(dp, encryptedMessageAppendix, blockchainHeight) {
            require(encryptedMessageAppendix.type == BrsApi.EncryptedMessageAppendix.Type.TO_SELF)
        }

        override fun putMyJSON(attachment: JsonObject) {
            val encryptToSelfMessageJSON = JsonObject()
            super.putMyJSON(encryptToSelfMessageJSON)
            attachment.add("encryptToSelfMessage", encryptToSelfMessageJSON)
        }

        companion object {
            fun parse(attachmentData: JsonObject): EncryptToSelfMessage? {
                return if (attachmentData.get("encryptToSelfMessage") == null) {
                    null
                } else EncryptToSelfMessage(attachmentData)
            }
        }
    }

    class PublicKeyAnnouncement : AbstractAppendix {
        private val dp: DependencyProvider
        val publicKey: ByteArray

        override fun getAppendixName(): String = "PublicKeyAnnouncement"

        override val mySize: Int
            get() = 32

        override val protobufMessage: Any
            get() = Any.pack(
                BrsApi.PublicKeyAnnouncementAppendix.newBuilder()
                    .setVersion(super.version.toInt())
                    .setRecipientPublicKey(publicKey.toByteString())
                    .build()
            )

        constructor(dp: DependencyProvider, buffer: ByteBuffer, transactionVersion: Byte) : super(
            buffer,
            transactionVersion
        ) {
            this.publicKey = ByteArray(32)
            this.dp = dp
            buffer.get(this.publicKey)
        }

        internal constructor(dp: DependencyProvider, attachmentData: JsonObject) : super(attachmentData) {
            this.publicKey =
                attachmentData.mustGetMemberAsString("recipientPublicKey").parseHexString()
            this.dp = dp
        }

        constructor(dp: DependencyProvider, publicKey: ByteArray, blockchainHeight: Int) : super(dp, blockchainHeight) {
            this.publicKey = publicKey
            this.dp = dp
        }

        constructor(
            dp: DependencyProvider,
            publicKeyAnnouncementAppendix: BrsApi.PublicKeyAnnouncementAppendix,
            blockchainHeight: Int
        ) : super(dp, blockchainHeight) {
            this.publicKey = publicKeyAnnouncementAppendix.recipientPublicKey.toByteArray()
            this.dp = dp
        }

        override fun putMyBytes(buffer: ByteBuffer) {
            buffer.put(publicKey)
        }

        override fun putMyJSON(attachment: JsonObject) {
            attachment.addProperty("recipientPublicKey", publicKey.toHexString())
        }

        override fun preValidate(transaction: Transaction, height: Int) {
            if (!transaction.type.hasRecipient()) {
                throw BurstException.NotValidException("PublicKeyAnnouncement cannot be attached to transactions with no recipient")
            }
            if (publicKey.size != 32) {
                throw BurstException.NotValidException("Invalid recipient public key length: " + publicKey.toHexString())
            }
            val recipientId = transaction.recipientId
            if (this.publicKey.publicKeyToId() != recipientId) {
                throw BurstException.NotValidException("Announced public key does not match recipient accountId")
            }
            if (transaction.version.toInt() == 0) {
                throw BurstException.NotValidException("Public key announcements not enabled for version 0 transactions")
            }
        }

        override fun validate(transaction: Transaction) {
            val recipientId = transaction.recipientId
            if (this.publicKey.publicKeyToId() != recipientId) {
                throw BurstException.NotValidException("Announced public key does not match recipient accountId")
            }
            val recipientAccount = dp.accountService.getAccount(recipientId)
            if (recipientAccount?.publicKey != null && !publicKey.contentEquals(recipientAccount.publicKey!!)) {
                throw BurstException.NotCurrentlyValidException("A different public key for this account has already been announced. Previous public key: \"${recipientAccount.publicKey.toHexString()}\", new public key: \"${publicKey.toHexString()}\"")
            }
        }

        override fun apply(transaction: Transaction, senderAccount: Account, recipientAccount: Account) {
            if (dp.accountStore.setOrVerify(recipientAccount, publicKey, transaction.height)) {
                recipientAccount.apply(dp, this.publicKey, transaction.height)
            }
        }

        companion object {
            fun parse(dp: DependencyProvider, attachmentData: JsonObject): PublicKeyAnnouncement? {
                return if (attachmentData.get("recipientPublicKey") == null) {
                    null
                } else PublicKeyAnnouncement(dp, attachmentData)
            }
        }
    }
}
