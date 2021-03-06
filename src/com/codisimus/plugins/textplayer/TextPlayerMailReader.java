package com.codisimus.plugins.textplayer;

import java.io.*;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.*;
import javax.mail.Flags.Flag;
import javax.mail.event.MessageCountEvent;
import javax.mail.event.MessageCountListener;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import sun.misc.BASE64Decoder;

/**
 * Sends and receives emails for the TextPlayer Plugin
 *
 * @author Codisimus
 */
public class TextPlayerMailReader implements MessageCountListener {
    public static enum Action {
        DISABLE, PL, PLAYERLIST, PLAYERS, WHO, FIND, TELL, TEXT, SAY, CHATMODE
    }
    public static boolean debug;
    public static boolean notify;
    public static String smtphost;
    public static int smtpport;
    public static String pop3host;
    public static String imaphost;
    public static int imapport;
    public static boolean imap;
    public static String username;
    public static String pass;
    public static int interval;
    public static BASE64Decoder decoder = new BASE64Decoder();
    private static Session session;
    private static Store store;
    private static Folder inbox;
    private static boolean processing;
    private static BukkitTask mailChecker;
    private static TextPlayerMailReader mailListener;

    /* Strings */
    private static final String SENDING = "Sending Message...";
    private static final String NO_EMAIL = "User has not set their Number/E-mail";
    private static final String ONLINE = "User is currently online";
    private static final String NOT_VERIFIED = "User's Number/Email has not been verified";
    private static final String LIMIT_REACHED = "User maxed out their text limit";
    private static final String SENT = "Message Sent!";
    private static final String FAILED = "Sending Failed";
    private static final String CONNECTION_ERROR = "Could not connect to email account, check settings in email.properties";
    private static final String TEXTPLAYER_TAG = "[TextPlayer] ";
    private static final String CHAT_MODE = "(Chat Mode) ";
    private static final String MULTIPART_TYPE = "multipart/*";
    private static final String TEXT_TYPE = "text/plain";
    private static final String HTML_TYPE = "text/html";
    private static final String OTHER_TYPE = "other";

    @Override
    public void messagesAdded(MessageCountEvent mce) {
        processing = true;
        readMessages(mce.getMessages());
        processing = false;
    }

    @Override
    public void messagesRemoved(MessageCountEvent mce) {
    }

    public static void checkMail() {
        processing = true;
        try {
            //Log in to the email account and retrieve the inbox
            if (!store.isConnected()) {
                store.connect(username, TextPlayer.encrypter.decrypt(pass));
                if (!store.isConnected()) {
                    TextPlayer.logger.severe(CONNECTION_ERROR);
                    cancelMailListener();
                    return;
                }
            }
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            readMessages(inbox.getMessages());

            closeInbox();
            if (!imap) {
                store.close();
            }
        } catch (Exception ex) {
            TextPlayer.logger.info("Could not read incoming mail!");
            cancelMailListener();
            ex.printStackTrace();
            MailListener();
        }
        processing = false;
    }

    private static void readMessages(Message[] messages) {
        for (Message message: messages) {
            try {
                if (debug) {
                    TextPlayer.logger.log(Level.INFO, "(Debug) Message received: " + streamToString(message.getInputStream()));
                }
                String msg = getMsg(message);
                if (debug) {
                    TextPlayer.logger.log(Level.INFO, "(Debug) Message body: " + msg);
                }

                msg = cleanUp(msg);
                if (debug) {
                    TextPlayer.logger.log(Level.INFO, "(Debug) Message after clean-up: " + msg);
                }

                String[] split = msg.split(" ");

                //Discover the User who sent the message
                String address = message.getFrom()[0].toString().toLowerCase();
                User user = TextPlayer.findUserByEmail(address);

                if (user == null) {
                    //Check if the message is a Confirmation Code
                    if (split[0].matches("[0-9][0-9][0-9][0-9]")) {
                        user = TextPlayer.findUserByCode(Integer.parseInt(split[0]));
                        if (user == null) {
                            if (debug) {
                                TextPlayer.logger.log(Level.INFO, "(Debug) Invalid Confirmation Code");
                            }
                            return;
                        }

                        user.emailIn = TextPlayer.encrypter.encrypt(address);
                        user.textLimit = 0;
                        user.save();
                        user.sendText(TEXTPLAYER_TAG + "Successfully Linked!", "Number/Email linked to " + user.name);
                        if (debug) {
                            TextPlayer.logger.log(Level.INFO, "(Debug) Address was succesfully linked to " + user.name);
                        }
                    } else {
                        if (notify) {
                            TextPlayer.logger.info("Message from unknown address, Message thrown out");
                        }
                        if (debug) {
                            TextPlayer.logger.log(Level.INFO, "(Debug) Unknown address: " + address);
                        }
                    }
                } else {
                    if (debug) {
                        TextPlayer.logger.log(Level.INFO, "(Debug) Message received from: " + user.name);
                    }

                    try {
                        Action action = Action.valueOf(split[0].toUpperCase());
                        switch (action) {
                        case DISABLE: //Set the User as not verified
                            user.sendText(TEXTPLAYER_TAG + "disabled", "Messages to this address have been disabled");
                            Thread.sleep(5000);
                            user.emailOut = "";
                            user.emailIn = "";
                            user.save();
                            break;

                        case PL: //Fall through
                        case PLAYERS: //Fall through
                        case WHO: //Fall through
                        case PLAYERLIST: //Construct a Player count/list to send
                            String subject = "Player Count: " + TextPlayer.server.getOnlinePlayers().length;
                            String list = "";
                            for (Player player : TextPlayer.server.getOnlinePlayers()) {
                                list = list.concat(", " + player.getName());
                            }

                            user.sendText(subject, list);
                            break;

                        case FIND: //Find if a Player is online
                            Player foundPlayer = TextPlayer.server.getPlayer(split[1].trim());
                            String status = foundPlayer == null ? "online" : "offline";
                            user.sendText(TEXTPLAYER_TAG + "reply for input", foundPlayer.getName() + " is currently " + status);
                            break;

                        case TELL: //Whisper a message to a Player
                            Player player = TextPlayer.server.getPlayer(split[1]);
                            if (player == null) {
                                user.sendText(TEXTPLAYER_TAG + "msg not sent!", player.getName() + " is currently offline");
                            }
                            else {
                                player.sendMessage("§5Text from §6" + user.name + "§f: §2"
                                        + msg.substring(split[0].length() + split[1].length() + 1));
                            }

                            break;

                        case TEXT: //Send a message to a User
                            User user2 = TextPlayer.findUser(split[1]);
                            if (user2 == null) {
                                user.sendText(TEXTPLAYER_TAG + "msg not sent!", split[1] + " does not have a TextPlayer account");
                            } else {
                                user.sendText("New Text from" + user.name, user.name + ":"
                                        + msg.substring(split[0].length()
                                        + split[1].length() + 1));
                            }

                            break;

                        case SAY: //Broadcast a message
                            TextPlayer.server.broadcastMessage(ChatColor.DARK_PURPLE + TEXTPLAYER_TAG
                                    + user.name + ChatColor.WHITE + msg.substring(3));
                            break;

                        case CHATMODE: //Toggle Chat Mode
                            user.chatMode = !user.chatMode;
                            user.sendText("TextPlayer Chat Mode", "Chat Mode has been " + (user.chatMode ? "enabled" : "disabled"));
                            break;

                        default: break;
                        }
                    } catch (Exception e) {
                        if (user.chatMode) {
                            TextPlayer.server.broadcastMessage(ChatColor.DARK_PURPLE + TEXTPLAYER_TAG
                                    + CHAT_MODE + user.name + ChatColor.WHITE + msg.substring(3));
                        } else if (user.isAdmin()) {
                            if (split[0].equals("rl")) {
                                //Delete the Message after reading it
                                message.setFlag(Flag.DELETED, true);

                                closeInbox();
                                store.close();

                                //Reload Server
                                TextPlayer.server.dispatchCommand(new TextPlayerCommandSender(user), msg);
                                return;
                            } else {
                                TextPlayer.server.dispatchCommand(new TextPlayerCommandSender(user), msg);
                            }
                        } else {
                            user.sendText("Permission denied!", "You must be an Admin to do that");
                        }
                    }
                }
            } catch (Exception e) {
                if (notify) {
                    TextPlayer.logger.info("Error reading email, Message thrown out");
                }
                if (debug) {
                    e.printStackTrace();
                }
            }

            try { message.setFlag(Flag.DELETED, true); } catch (Exception e) {}
        }
    }

    /**
     * Returns the given Message as a String
     *
     * @param message The given Message to convert
     * @return The String representation of the Message
     * @throws Exception If anything goes wrong
     */
    private static String getMsg(Message message) throws Exception {
        HashMap<String, InputStream> parts = findMimeTypes(message);
        InputStream is = null;
        if (parts.containsKey(TEXT_TYPE)) {
            is = parts.get(TEXT_TYPE);
        } else if (parts.containsKey(HTML_TYPE)) {
            is = parts.get(HTML_TYPE);
        } else {
            is = message.getInputStream();
            DataSource source = new ByteArrayDataSource(is, MULTIPART_TYPE);
            Multipart mp = new MimeMultipart(source);
            parts = findMimeTypes(mp.getBodyPart(0));
            is = parts.get(TEXT_TYPE);
        }

        return streamToString(is);
    }

    private static HashMap<String, InputStream> findMimeTypes(Part p) {
        HashMap<String, InputStream> parts = new HashMap<String, InputStream>();
        findMimeTypesHelper(p, parts);
        return parts;
    }

    private static HashMap<String, InputStream> findMimeTypes(Multipart mp) {
        HashMap<String, InputStream> parts = new HashMap<String, InputStream>();
        try {
            for (int i = 0; i < mp.getCount(); i++) {
                findMimeTypesHelper(mp.getBodyPart(i), parts);
            }
        } catch (Exception ex) {
        }
        return parts;
    }

    // a little recursive helper function that actually does all the work.
    private static void findMimeTypesHelper(Part p, HashMap<String, InputStream> parts) {
        try {
            if (p.isMimeType(TEXT_TYPE)) {
                if (!parts.containsKey(TEXT_TYPE)) {
                    parts.put(TEXT_TYPE, p.getInputStream());
                }
            } else if (p.isMimeType(HTML_TYPE)) {
                if (!parts.containsKey(HTML_TYPE)) {
                    parts.put(HTML_TYPE, p.getInputStream());
                }
            } else {
                Object content = p.getContent();
                if (content instanceof MimeMultipart) {
                    MimeMultipart mmp = (MimeMultipart) content;
                    for (int i = 0; i < mmp.getCount(); i++) {
                        findMimeTypesHelper(mmp.getBodyPart(i), parts);
                    }
                } else if (content instanceof Multipart) {
                    Multipart mp = (Multipart) content;
                    for (int i = 0; i < mp.getCount(); i++) {
                        findMimeTypesHelper(mp.getBodyPart(i), parts);
                    }
                } else {
                    DataSource source = new ByteArrayDataSource(p.getInputStream(), MULTIPART_TYPE);
                    Multipart mp = new MimeMultipart(source);
                    parts = findMimeTypes(mp.getBodyPart(0));
                    for (int i = 0; i < mp.getCount(); i++) {
                        findMimeTypesHelper(mp.getBodyPart(i), parts);
                    }
                }
            }
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Returns the given InputStream as a String
     *
     * @param is The given InputStream
     * @return The String representation of the InputStream
     * @throws Exception If anything goes wrong
     */
    private static String streamToString(InputStream is) throws Exception {
        //Return an empty string if no InputStream was given
        if (is == null) {
            return "";
        }

        Writer writer = new StringWriter();
        char[] buffer = new char[1024];

        try {
            Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            int n;
            while ((n = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, n);
            }
        } finally {
            is.close();
        }

        return writer.toString();
    }

    /**
     * Cleans up the given String
     *
     * @param msg The String to be cleaned
     * @return The cleaned String
     */
    private static String cleanUp(String msg) {
        //Eliminate all 'RE:'s
        if (msg.contains("RE:")) {
            msg = msg.replace("RE:", "");
        }

        //Eliminate white space before the first word
        while (msg.startsWith(" ") || msg.startsWith("\n")) {
            msg = msg.substring(msg.startsWith("\n") ? 2 : 1);
        }

        //Throw out everything but the first line and trim white space off of the end
        msg = msg.split("\n")[0].trim();

        //Change the first letter to lowercase and return the String
        msg = msg.length() > 2 ? msg.substring(0, 1).toLowerCase().concat(msg.substring(1)) : msg.toLowerCase();
        return msg;
    }

    /**
     * Checks for new email
     */
    public static void MailListener() {
        Properties props = System.getProperties();
        //props.setProperty("mail.smtp.ssl.trust", "smtpserver");
        //props.setProperty("mail.smtp.starttls.enable", "true");
        //props.setProperty("mail.smtp.auth", "true");
        if (imap) {
            props.setProperty("mail.imap.host", imaphost);
            props.setProperty("mail.imap.port", String.valueOf(imapport));
        } else {
            props.setProperty("mail.pop3.host", pop3host);
            if (pop3host.equals("pop.gmail.com")) {
                // Start SSL connection
                props.setProperty("mail.pop3.socketFactory" , "995");
                props.setProperty("mail.pop3.socketFactory.class" , "javax.net.ssl.SSLSocketFactory");
                props.setProperty("mail.pop3.port" , "995");
            }
        }

        //Verify the Username and Password
        session = Session.getInstance(props,
        new javax.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, TextPlayer.encrypter.decrypt(pass));
            }
        });

        //session.setDebug(debug);
        try {
            store = session.getStore(imap ? "imap" : "pop3");
            if (imap) {
                store.connect(username, TextPlayer.encrypter.decrypt(pass));
                if (!store.isConnected()) {
                    TextPlayer.logger.severe(CONNECTION_ERROR);
                    cancelMailListener();
                    return;
                }
                inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);
            }
        } catch (Exception ex) {
            TextPlayer.logger.severe("Cannot read incoming mail!");
            ex.printStackTrace();
        }

        if (imap) {
            inbox.addMessageCountListener(new TextPlayerMailReader());
        } else {
            if (interval == 0) {
                TextPlayer.logger.info("Only checking for new mail on command '/text check'");
            } else {
                mailChecker = TextPlayer.server.getScheduler().runTaskTimerAsynchronously(TextPlayer.plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!processing) {
                            checkMail();
                        }
                    }
                }, 0L, 20L * interval);

                TextPlayer.logger.info("Checking for new mail every " + interval + " seconds");
            }
        }
    }

    /**
     * Checks for new email
     */
    public static void forceCheck(final Player player) {
        TextPlayer.server.getScheduler().runTaskAsynchronously(TextPlayer.plugin, new Runnable() {
            @Override
            public void run() {
                if (processing) {
                    if (player != null) {
                        player.sendMessage("§4Mail check is already in progress.");
                    }
                    return;
                }

                if (player != null) {
                    player.sendMessage("§5Checking for new mail...");
                }

                checkMail();

                if (player != null) {
                    player.sendMessage("§5Finished checking for new mail.");
                }
            }
        });
    }

    private static void cancelMailListener() {
        if (mailChecker != null) {
            mailChecker.cancel();
        }
        if (mailListener != null) {
            inbox.removeMessageCountListener(mailListener);
            closeInbox();
        }
    }

    private static void closeInbox() {
        try {
            inbox.close(true);
        } catch (Exception e) {
        }
    }

    public static void sendMsg(final Player player, final User user, final String subject, final String body) {
        //Start a new Thread
        TextPlayer.server.getScheduler().runTaskAsynchronously(TextPlayer.plugin, new Runnable() {
            @Override
            public void run() {
                //Notify the Server log if set to in the config
                if (notify) {
                    TextPlayer.logger.info(SENDING);
                }

                //Notify the Player if there is one
                if (player != null) {
                    player.sendMessage(ChatColor.DARK_PURPLE + SENDING);
                }

                //Cancel if the User has not set their E-mail address
                if (user.emailOut.isEmpty()) {
                    //Notify the Server log if set to in the config
                    if (notify) {
                        TextPlayer.logger.info(NO_EMAIL);
                    }

                    //Notify the Player if there is one
                    if (player != null) {
                        player.sendMessage(ChatColor.DARK_RED + NO_EMAIL);
                    }

                    return;
                }

                //Cancel if the User is online and has disabled when logged set to true
                if (user.disableWhenLogged && TextPlayerListener.online.contains(user.name)
                        && !subject.startsWith(TEXTPLAYER_TAG)) {
                    //Notify the Server log if set to in the config
                    if (notify) {
                        TextPlayer.logger.info(ONLINE);
                    }

                    //Notify the Player if there is one
                    if (player != null) {
                        player.sendMessage(ChatColor.DARK_RED + ONLINE);
                    }

                    return;
                }

                //Cancel if the User is not verified
                if (user.textLimit < 0 && !subject.startsWith(TEXTPLAYER_TAG)) {
                    //Notify the Server log if set to in the config
                    if (notify) {
                        TextPlayer.logger.info(NOT_VERIFIED);
                    }

                    //Notify the Player if there is one
                    if (player != null) {
                        player.sendMessage(ChatColor.DARK_RED + NOT_VERIFIED);
                    }

                    return;
                }

                String msg;
                //Check if the User has a text limit
                if (user.textLimit > 0) {
                    //Reset the amount of texts sent if the last text was sent on a previous day
                    int day = Calendar.getInstance().DAY_OF_YEAR;
                    if (user.lastText != day) {
                        user.lastText = day;
                        user.textsSent = 0;
                    } else {
                        //Cancel if the User maxed out their text limit
                        if (user.textsSent >= user.textLimit) {
                            //Notify the Server log if set to in the config
                            if (notify) {
                                TextPlayer.logger.info(LIMIT_REACHED);
                            }

                            //Notify the Player if there is one
                            if (player != null) {
                                player.sendMessage(ChatColor.DARK_RED + LIMIT_REACHED);
                            }

                            return;
                        }
                    }

                    //Notify the User if there is less than 2 messages remaining
                    switch (user.textLimit - user.textsSent++) {
                    case 0: msg = body.concat(" *last txt 4 2day"); break;
                    case 1: msg = body.concat(" *1 txt left 4 2day"); break;
                    default: msg = body; break;
                    }

                    user.save();
                } else {
                    msg = body;
                }

                Properties props = System.getProperties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.socketFactory.port", smtpport);
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", smtphost);
                props.put("mail.smtp.port", smtpport);

                //Verify the Username and Password
                Session session = Session.getInstance(props,
                new javax.mail.Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, TextPlayer.encrypter.decrypt(pass));
                    }
                });

                session.setDebug(debug);

                try {
                    Message message = new MimeMessage(session);
                    message.setFrom(new InternetAddress(username));
                    message.setRecipients(Message.RecipientType.TO,
                            InternetAddress.parse(TextPlayer.encrypter.decrypt(user.emailOut)));
                    message.setSubject(subject);
                    message.setText(msg);
//                    Transport.send(message);

                    Transport transport = session.getTransport("smtp");
                    transport.connect(smtphost, smtpport, username, TextPlayer.encrypter.decrypt(pass));
                    transport.sendMessage(message, message.getAllRecipients());

                    //Notify the Server log if set to in the config
                    if (notify) {
                        TextPlayer.logger.info(SENT);
                    }

                    //Notify the Player if there is one
                    if (player != null) {
                        player.sendMessage(ChatColor.DARK_PURPLE + SENT);
                    }
                } catch (Exception sendFailed) {
                    //Notify the Server log if set to in the config
                    if (notify) {
                        TextPlayer.logger.info(FAILED);
                    }

                    //Notify the Player if there is one
                    if (player != null) {
                        player.sendMessage(ChatColor.DARK_RED + FAILED);
                    }

                    sendFailed.printStackTrace();
                }
            }
        });
    }
}
