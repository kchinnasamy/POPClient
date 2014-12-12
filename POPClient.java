import com.sun.xml.internal.messaging.saaj.packaging.mime.MessagingException;
import com.sun.xml.internal.messaging.saaj.packaging.mime.internet.MimeUtility;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class POP {

    private static final int TIMEOUT = 10000;

    private Socket popSocket;
    private BufferedReader in;
    private PrintWriter out;

    private String host;
    private int port;
    private String userName;
    private String password;


    public POP(String host, int port, String userName, String password) throws IOException {
        this.host = host;
        this.port = port;
        this.userName = userName;
        this.password = password;
        makeConnection(host, port);
        login(userName, password);

    }

    private void makeConnection(String host, int port) throws IOException {
        Security.addProvider(
                new com.sun.net.ssl.internal.ssl.Provider());

        SSLSocketFactory factory =
                (SSLSocketFactory) SSLSocketFactory.getDefault();
        popSocket = factory.createSocket(host, port);
        popSocket.setSoTimeout(TIMEOUT);
        in = new BufferedReader(new InputStreamReader(popSocket.getInputStream()));
        out = new PrintWriter(new OutputStreamWriter(popSocket.getOutputStream()));

        String response = in.readLine();
        verifyResponse(response);
    }

    private void verifyResponse(String response) throws IOException {
        if (response.charAt(0) != '+') {
            throw new IOException(response);

        }

    }

    private void login(String userName, String password) throws IOException {
        //USERNAME
        out.println("USER " + userName);
        out.flush();
        verifyResponse(in.readLine());

        //PASSWORD
        out.println("PASS " + password);
        out.flush();
        verifyResponse(in.readLine());
    }

    public Mail readMessage(String userId, MailMeta mailMeta) throws IOException {
        out.println("RETR " + mailMeta.getMailId());
        out.flush();
        verifyResponse(in.readLine());
//        String line;
//        while (!(line = in.readLine()).equalsIgnoreCase(".")) {
//            System.out.println(line);
//        }
        return loadMail(userId, mailMeta);
    }

    private Mail loadMail(String userId, MailMeta mailMeta) {
        boolean isStartOfContent = false;
        String line;
        Mail mail = new Mail(); // Model Class used to save the data
        MailContentType mailContentType = null; // Model Class used to save the data
        mail.setOutgoing(false);
        mail.setTag(Constants.TAG_INBOX);
        mail.setMailId(mailMeta.getuId());
        mail.setUserId(userId);
        mail.setHasAttachment(false);
        mail.setRead(false);
        StringBuffer content = new StringBuffer();
        Attachment multiPartData = new Attachment(mail.getUserId(), mail.getMailId());
        try {
            while (!(line = in.readLine()).equalsIgnoreCase(".")) {
                if (!isStartOfContent) {
                    String[] lineContent = line.split(": ");
//                    if (lineContent[0].equals("Delivered-To")) {
//                        mail.setOutgoing(true);
//                        mail.setTag(Constants.TAG_SENT);
//                    } else
                    if (lineContent[0].equals("Date")) {
                        mail.setDate((lineContent[1]));
                    } else if (lineContent[0].equals("Subject")) {
                        if (lineContent.length > 1) {
                            mail.setSubject(lineContent[1]);
                        } else {
                            mail.setSubject("");
                        }
                    } else if (lineContent[0].equals("From")) {
                        mail.setFrom(StringEscapeUtils.escapeHtml4(lineContent[1]));
                    } else if (lineContent[0].equals("To")) {
                        mail.setTo(StringEscapeUtils.escapeHtml4(lineContent[1]));
                    } else if (lineContent[0].equals("Content-Type")) {
                        String[] contentType = lineContent[1].split("; ");
                        if (contentType.length > 1) {
                            String[] boundary = contentType[1].split("=");
                            if (boundary[0].equals("boundary")) {
                                mailContentType = new MailContentType(contentType[0], boundary[1]);
                                isStartOfContent = true;
                            }
                        }
//                        else {
//                            //This is when there is no attachment and the rest of the data is the actual content
//                            //Example in yahoo, a mail without any attachment
//                            multiPartData.setAttachment(false);
//                            multiPartData.setContentType(contentType[0]);
//                            return loadMainContent(in, mail, multiPartData);
//
//                        }
                    } else if (lineContent[0].equals("Content-Transfer-Encoding")) {
                        multiPartData.setEncoding(lineContent[1]);
                    }
                } else {
                    loadContent(in, mail, mailContentType.getBoundary(), mail.getAttachments(), content);
                }
            }
            mail.setSize(content.length());
            return mail;
        } catch (IOException e) {
            ErrorManager.instance().error(e);
        } catch (MessagingException e) {
            ErrorManager.instance().error(e);
        }
        return null;
    }

    private Mail loadMainContent(BufferedReader in, Mail mail, Attachment multiPartData) throws IOException, MessagingException {
        String line;
        StringBuffer content = new StringBuffer();
        while (!(line = in.readLine()).equalsIgnoreCase(".")) {
            content.append(line);
        }
        multiPartData.setContent(content);
        if (multiPartData.getContentType().equals("text/html")) {
            String decode = multiPartData.getContent().toString();
            if (multiPartData.getEncoding().equalsIgnoreCase("quoted-printable")) {
                decode = getDecodeString(multiPartData);
            }
            mail.setBody(decode);
        }

        return mail;
    }

    private void loadContent(BufferedReader in, Mail mail, String boundary, ArrayList<Attachment> attachments, StringBuffer content) throws IOException, MessagingException {
        String line;
        boolean isStartOfContent = false;
        Attachment multiPartData = null;
        while (!(line = in.readLine()).equals(endOfBoundary(boundary))) {
            content.append(line);
            if (isStartOfBoundary(line, boundary)) {
                //during recursion check for the switch between inner-content and outer-content
                if (multiPartData != null && !multiPartData.getContent().toString().equals("")) {
                    setDataToAttachmentOrMail(mail, attachments, multiPartData);
                }
                multiPartData = new Attachment(mail.getUserId(), mail.getMailId());

                isStartOfContent = false;
            }
            if (!isStartOfContent) {
                String[] lineContent = line.split(": ");
                if (lineContent[0].equals("Content-Type")) {
                    String[] contentType = lineContent[1].split(";");
                    if (contentType[0].equals("multipart/alternative")) {
                        //check if the content has an other multipart as the content
                        String[] newBoundary = lineContent[1].split("boundary=");
                        if (newBoundary.length > 1) {
                            loadContent(in, mail, newBoundary[1], attachments, content);
                        }
                    } else {
                        multiPartData.setContentType(contentType[0]);
                    }
                } else if (!isStartOfContent && lineContent[0].equals("")) {
                    isStartOfContent = true;
                } else if (lineContent[0].equals("Content-Disposition")) {
                    if (lineContent[1].startsWith("attachment")) {
                        mail.setHasAttachment(true);
                        multiPartData.setAttachment(true);
                        multiPartData.setFileName((lineContent[1].split("filename=")[1]).replace("\"", ""));
                    }
                } else if (lineContent[0].equals("Content-Transfer-Encoding")) {
                    multiPartData.setEncoding(lineContent[1]);
                }
            } else {
                multiPartData.getContent().append(line);
                multiPartData.getContent().append(System.getProperty("line.separator"));
            }
        }
        //add the last parsed mail multipart content ,if its has any data
        //This would be the mail body data because it's final loop of the recursion
        if (multiPartData.getContentType() != null) {
            setDataToAttachmentOrMail(mail, attachments, multiPartData);
        }
    }

    private void setDataToAttachmentOrMail(Mail mail, ArrayList<Attachment> attachments, Attachment multiPartData) throws IOException, MessagingException {
        if (multiPartData.isAttachment()) {
            multiPartData.setSize(multiPartData.getContent().length());
            mail.setHasAttachment(true);
            attachments.add(multiPartData);
        } else {
            if (multiPartData.getContentType().equals("text/html")) {
                String decode = multiPartData.getContent().toString();
                if (multiPartData.getEncoding().equalsIgnoreCase("quoted-printable")) {
                    decode = getDecodeString(multiPartData);
                }
                //Replace the body with div tags so that it doesn't mess up the page
                Document doc = Jsoup.parse(decode);
                Elements elements = doc.select("body");
                elements.tagName("div");
                mail.setBody(doc.html());
            }
        }
    }

    private String getDecodeString(Attachment multiPartData) throws IOException, MessagingException {
        InputStream stream = new ByteArrayInputStream(multiPartData.getContent().toString().getBytes(StandardCharsets.UTF_8));
        return IOUtils.toString(MimeUtility.decode(stream, multiPartData.getEncoding()), StandardCharsets.UTF_8);
    }

    private String endOfBoundary(String boundary) {
        return "--" + boundary + "--";
    }

    private boolean isStartOfBoundary(String line, String boundary) {
        if (line.equals("--" + boundary)) {
            return true;
        }
        return false;
    }

    public ArrayList<Mail> getAllMails(String userId) throws IOException {
        ArrayList<Mail> mails = new ArrayList<Mail>();
        for (MailMeta mailMeta : getMailList()) {
            mails.add(readMessage(userId, mailMeta));
        }
        return mails;
    }

    private ArrayList<MailMeta> getMailList() throws IOException {
        out.println("UIDL");
        out.flush();
        String line = in.readLine();
        verifyResponse(line);
        ArrayList<MailMeta> mailMetas = new ArrayList<MailMeta>();
        while (!(line = in.readLine()).equalsIgnoreCase(".")) {
            String[] response = line.split(" ");
            mailMetas.add(new MailMeta(Integer.parseInt(response[0]), (response[1])));
        }
        return mailMetas;
    }

    public int getTotalMailCount() throws IOException {
        out.println("STAT");
        out.flush();
        String line = in.readLine();
        verifyResponse(line);
        String[] response = line.split(" ");
        return Integer.parseInt(response[1]);
    }

    public void close() {
        out.println("QUIT");
        out.flush();
        out.close();
        try {
            in.close();
            popSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private String convertDBDate(String date) throws ParseException {
        SimpleDateFormat formatter = new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss Z");
        Date dbDate = formatter.parse(date);
        SimpleDateFormat dbFormat = new SimpleDateFormat("YYYY-MM-dd HH:mm:ss");
        return dbFormat.format(dbDate);
    }

}
