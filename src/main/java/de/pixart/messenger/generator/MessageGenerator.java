package de.pixart.messenger.generator;

import net.java.otr4j.OtrException;
import net.java.otr4j.session.Session;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import de.pixart.messenger.Config;
import de.pixart.messenger.crypto.axolotl.AxolotlService;
import de.pixart.messenger.crypto.axolotl.XmppAxolotlMessage;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.Contact;
import de.pixart.messenger.entities.Conversation;
import de.pixart.messenger.entities.Message;
import de.pixart.messenger.http.P1S3UrlStreamHandler;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.utils.Namespace;
import de.pixart.messenger.xml.Element;
import de.pixart.messenger.xmpp.chatstate.ChatState;
import de.pixart.messenger.xmpp.stanzas.MessagePacket;
import rocks.xmpp.addr.Jid;

public class MessageGenerator extends AbstractGenerator {
    public static final String OTR_FALLBACK_MESSAGE = "I would like to start a private (OTR encrypted) conversation but your client doesn’t seem to support that";
    private static final String OMEMO_FALLBACK_MESSAGE = "I sent you an OMEMO encrypted message but your client doesn’t seem to support that. Find more information on https://conversations.im/omemo";
    private static final String PGP_FALLBACK_MESSAGE = "I sent you a PGP encrypted message but your client doesn’t seem to support that.";

    public MessageGenerator(XmppConnectionService service) {
        super(service);
    }

    private MessagePacket preparePacket(Message message) {
        Conversation conversation = (Conversation) message.getConversation();
        Account account = conversation.getAccount();
        MessagePacket packet = new MessagePacket();
        final boolean isWithSelf = conversation.getContact().isSelf();
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            packet.setTo(message.getCounterpart());
            packet.setType(MessagePacket.TYPE_CHAT);
            if (this.mXmppConnectionService.indicateReceived() && !isWithSelf) {
                packet.addChild("request", "urn:xmpp:receipts");
            }
        } else if (message.getType() == Message.TYPE_PRIVATE) { //TODO files and images might be private as well
            packet.setTo(message.getCounterpart());
            packet.setType(MessagePacket.TYPE_CHAT);
            packet.addChild("x","http://jabber.org/protocol/muc#user");
            if (this.mXmppConnectionService.indicateReceived()) {
                packet.addChild("request", "urn:xmpp:receipts");
            }
        } else {
            packet.setTo(message.getCounterpart().asBareJid());
            packet.setType(MessagePacket.TYPE_GROUPCHAT);
        }
        if (conversation.isSingleOrPrivateAndNonAnonymous() && message.getType() != Message.TYPE_PRIVATE) {
            packet.addChild("markable", "urn:xmpp:chat-markers:0");
        }
        packet.setFrom(account.getJid());
        packet.setId(message.getUuid());
        packet.addChild("origin-id", Namespace.STANZA_IDS).setAttribute("id", message.getUuid());
        if (message.edited()) {
            packet.addChild("replace", "urn:xmpp:message-correct:0").setAttribute("id", message.getEditedId());
        }
        return packet;
    }

    public void addDelay(MessagePacket packet, long timestamp) {
        final SimpleDateFormat mDateFormat = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        mDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Element delay = packet.addChild("delay", "urn:xmpp:delay");
        Date date = new Date(timestamp);
        delay.setAttribute("stamp", mDateFormat.format(date));
    }

    public MessagePacket generateAxolotlChat(Message message, XmppAxolotlMessage axolotlMessage) {
        MessagePacket packet = preparePacket(message);
        if (axolotlMessage == null) {
            return null;
        }
        packet.setAxolotlMessage(axolotlMessage.toElement());
        packet.setBody(OMEMO_FALLBACK_MESSAGE);
        packet.addChild("store", "urn:xmpp:hints");
        packet.addChild("encryption", "urn:xmpp:eme:0").setAttribute("name", "OMEMO").setAttribute("namespace", AxolotlService.PEP_PREFIX);
        return packet;
    }

    public MessagePacket generateKeyTransportMessage(Jid to, XmppAxolotlMessage axolotlMessage) {
        MessagePacket packet = new MessagePacket();
        packet.setType(MessagePacket.TYPE_CHAT);
        packet.setTo(to);
        packet.setAxolotlMessage(axolotlMessage.toElement());
        packet.addChild("store", "urn:xmpp:hints");
        return packet;
    }

    private static boolean recipientSupportsOmemo(Message message) {
        Contact c = message.getContact();
        return c != null && c.getPresences().allOrNonSupport(AxolotlService.PEP_DEVICE_LIST_NOTIFY);
    }

    public static void addMessageHints(MessagePacket packet) {
        packet.addChild("private", "urn:xmpp:carbons:2");
        packet.addChild("no-copy", "urn:xmpp:hints");
        packet.addChild("no-permanent-store", "urn:xmpp:hints");
        packet.addChild("no-permanent-storage", "urn:xmpp:hints"); //do not copy this. this is wrong. it is *store*
    }

    public MessagePacket generateOtrChat(Message message) {
        Conversation conversation = (Conversation) message.getConversation();
        Session otrSession = conversation.getOtrSession();
        if (otrSession == null) {
            return null;
        }
        MessagePacket packet = preparePacket(message);
        addMessageHints(packet);
        try {
            String content;
            if (message.hasFileOnRemoteHost()) {
                content = message.getFileParams().url.toString();
            } else {
                content = message.getBody();
            }
            packet.setBody(otrSession.transformSending(content)[0]);
            packet.addChild("encryption", "urn:xmpp:eme:0").setAttribute("namespace", "urn:xmpp:otr:0");
            return packet;
        } catch (OtrException e) {
            return null;
        }
    }

    public MessagePacket generateChat(Message message) {
        MessagePacket packet = preparePacket(message);
        String content;
        if (message.hasFileOnRemoteHost()) {
            Message.FileParams fileParams = message.getFileParams();
            final URL url = fileParams.url;
            if (P1S3UrlStreamHandler.PROTOCOL_NAME.equals(url.getProtocol())) {
                Element x = packet.addChild("x", Namespace.P1_S3_FILE_TRANSFER);
                final String file = url.getFile();
                x.setAttribute("name", file.charAt(0) == '/' ? file.substring(1) : file);
                x.setAttribute("fileid", url.getHost());
                return packet;
            } else {
                content = url.toString();
                packet.addChild("x", Namespace.OOB).addChild("url").setContent(content);
            }
        } else {
            content = message.getBody();
        }
        packet.setBody(content);
        return packet;
    }

    public MessagePacket generatePgpChat(Message message) {
        MessagePacket packet = preparePacket(message);
        if (message.hasFileOnRemoteHost()) {
            Message.FileParams fileParams = message.getFileParams();
            final URL url = fileParams.url;
            if (P1S3UrlStreamHandler.PROTOCOL_NAME.equals(url.getProtocol())) {
                Element x = packet.addChild("x", Namespace.P1_S3_FILE_TRANSFER);
                final String file = url.getFile();
                x.setAttribute("name", file.charAt(0) == '/' ? file.substring(1) : file);
                x.setAttribute("fileid", url.getHost());
            } else {
                packet.setBody(url.toString());
                packet.addChild("x", Namespace.OOB).addChild("url").setContent(url.toString());
            }
        } else {
            if (Config.supportUnencrypted()) {
                packet.setBody(PGP_FALLBACK_MESSAGE);
            }
            if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                packet.addChild("x", "jabber:x:encrypted").setContent(message.getEncryptedBody());
            } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
                packet.addChild("x", "jabber:x:encrypted").setContent(message.getBody());
            }
            packet.addChild("encryption", "urn:xmpp:eme:0")
                    .setAttribute("namespace", "jabber:x:encrypted");
        }
        return packet;
    }

    public MessagePacket generateChatState(Conversation conversation) {
        final Account account = conversation.getAccount();
        MessagePacket packet = new MessagePacket();
        packet.setType(conversation.getMode() == Conversation.MODE_MULTI ? MessagePacket.TYPE_GROUPCHAT : MessagePacket.TYPE_CHAT);
        packet.setTo(conversation.getJid().asBareJid());
        packet.setFrom(account.getJid());
        packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()));
        packet.addChild("no-store", "urn:xmpp:hints");
        packet.addChild("no-storage", "urn:xmpp:hints"); //wrong! don't copy this. Its *store*
        return packet;
    }

    public MessagePacket confirm(final Account account, final Jid to, final String id, final Jid counterpart, final boolean groupChat) {
        MessagePacket packet = new MessagePacket();
        packet.setType(groupChat ? MessagePacket.TYPE_GROUPCHAT : MessagePacket.TYPE_CHAT);
        packet.setTo(groupChat ? to.asBareJid() : to);
        packet.setFrom(account.getJid());
        Element displayed = packet.addChild("displayed", "urn:xmpp:chat-markers:0");
        displayed.setAttribute("id", id);
        if (groupChat && counterpart != null) {
            displayed.setAttribute("sender", counterpart.toString());
        }
        packet.addChild("store", "urn:xmpp:hints");
        return packet;
    }

    public MessagePacket conferenceSubject(Conversation conversation, String subject) {
        MessagePacket packet = new MessagePacket();
        packet.setType(MessagePacket.TYPE_GROUPCHAT);
        packet.setTo(conversation.getJid().asBareJid());
        Element subjectChild = new Element("subject");
        subjectChild.setContent(subject);
        packet.addChild(subjectChild);
        packet.setFrom(conversation.getAccount().getJid().asBareJid());
        return packet;
    }

    public MessagePacket directInvite(final Conversation conversation, final Jid contact) {
        MessagePacket packet = new MessagePacket();
        packet.setType(MessagePacket.TYPE_NORMAL);
        packet.setTo(contact);
        packet.setFrom(conversation.getAccount().getJid());
        Element x = packet.addChild("x", "jabber:x:conference");
        x.setAttribute("jid", conversation.getJid().asBareJid().toString());
        String password = conversation.getMucOptions().getPassword();
        if (password != null) {
            x.setAttribute("password", password);
        }
        return packet;
    }

    public MessagePacket invite(Conversation conversation, Jid contact) {
        MessagePacket packet = new MessagePacket();
        packet.setTo(conversation.getJid().asBareJid());
        packet.setFrom(conversation.getAccount().getJid());
        Element x = new Element("x");
        x.setAttribute("xmlns", "http://jabber.org/protocol/muc#user");
        Element invite = new Element("invite");
        invite.setAttribute("to", contact.asBareJid().toString());
        x.addChild(invite);
        packet.addChild(x);
        return packet;
    }

    public MessagePacket received(Account account, MessagePacket originalMessage, ArrayList<String> namespaces, int type) {
        MessagePacket receivedPacket = new MessagePacket();
        receivedPacket.setType(type);
        receivedPacket.setTo(originalMessage.getFrom());
        receivedPacket.setFrom(account.getJid());
        for (String namespace : namespaces) {
            receivedPacket.addChild("received", namespace).setAttribute("id", originalMessage.getId());
        }
        receivedPacket.addChild("store", "urn:xmpp:hints");
        return receivedPacket;
    }

    public MessagePacket received(Account account, Jid to, String id) {
        MessagePacket packet = new MessagePacket();
        packet.setFrom(account.getJid());
        packet.setTo(to);
        packet.addChild("received", "urn:xmpp:receipts").setAttribute("id", id);
        packet.addChild("store", "urn:xmpp:hints");
        return packet;
    }

    public MessagePacket generateOtrError(Jid to, String id, String errorText) {
        MessagePacket packet = new MessagePacket();
        packet.setType(MessagePacket.TYPE_ERROR);
        packet.setAttribute("id", id);
        packet.setTo(to);
        Element error = packet.addChild("error");
        error.setAttribute("code", "406");
        error.setAttribute("type", "modify");
        error.addChild("not-acceptable", "urn:ietf:params:xml:ns:xmpp-stanzas");
        error.addChild("text").setContent("?OTR Error:" + errorText);
        return packet;
    }
}
