/*
 * imoten - i mode.net mail tensou(forward)
 *
 * Copyright (C) 2013 ryu aka 508.P905 (http://code.google.com/p/imoten/)
 * Copyright (C) 2010 shoozhoo (http://code.google.com/p/imoten/)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package immf;

import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;

public class SpmodeForwardMail extends MyHtmlEmail {
	private static final Log log = LogFactory.getLog(SpmodeForwardMail.class);
	private Message smm;
	private String plainBody = "";
	private String htmlBody = "";
	private String smmSubject;
	private Date smmDate;
	private InternetAddress smmFromAddr;
	private List<InternetAddress> smmReplyToAddrList = new ArrayList<InternetAddress>();
	private List<InternetAddress> smmToAddrList = new ArrayList<InternetAddress>();
	private List<InternetAddress> smmCcAddrList = new ArrayList<InternetAddress>();
	private boolean decomeFlg = false;
	private boolean hasPlain = false;
	private Config conf;
	private CharacterConverter subjectCharConv = null;
	private CharacterConverter goomojiSubjectCharConv = null;
	private StringConverter strConv = null;
	private static Map<Config, CharacterConverter> subjectCharConvMap = null;
	private static Map<Config, CharacterConverter> goomojiSubjectCharConvMap = null;
	private static Map<Config, StringConverter> strConvMap = null;
	private StringBuilder headerInfo = new StringBuilder();

	public SpmodeForwardMail(Message sm, Config conf) throws EmailException{
		this.smm = sm;
		this.conf = conf;

		this.setDebug(conf.isMailDebugEnable());
		this.setCharset(this.conf.getMailEncode());
		this.setContentTransferEncoding(this.conf.getContentTransferEncoding());

		if(SpmodeForwardMail.subjectCharConvMap!=null
				&& SpmodeForwardMail.subjectCharConvMap.containsKey(conf)){
			this.subjectCharConv = SpmodeForwardMail.subjectCharConvMap.get(conf);
		}else{
			this.subjectCharConv = new CharacterConverter();
		}
		if(SpmodeForwardMail.goomojiSubjectCharConvMap!=null
				&& SpmodeForwardMail.goomojiSubjectCharConvMap.containsKey(conf)){
			this.goomojiSubjectCharConv = SpmodeForwardMail.goomojiSubjectCharConvMap.get(conf);
		}else{
			this.goomojiSubjectCharConv = new CharacterConverter();
		}
		if(SpmodeForwardMail.strConvMap!=null
				&& SpmodeForwardMail.strConvMap.containsKey(conf)){
			this.strConv = SpmodeForwardMail.strConvMap.get(conf);
		}else{
			this.strConv = new StringConverter();
		}

		// SMTP Server
		this.setHostName(conf.getSmtpServer());
		this.setSmtpPort(conf.getSmtpPort());
		this.setSocketConnectionTimeout(conf.getSmtpConnectTimeoutSec()*1000);
		this.setSocketTimeout(conf.getSmtpTimeoutSec()*1000);
		this.setTLS(conf.isSmtpTls());

		if(!StringUtils.isBlank(conf.getSmtpUser())){
			this.setAuthentication(conf.getSmtpUser(), conf.getSmtpPasswd());
		}

		if(!StringUtils.isBlank(conf.getPopServer())
				&& !StringUtils.isBlank(conf.getPopUser())){
			// POP before SMTP
			this.setPopBeforeSmtp(true, conf.getPopServer(), conf.getPopUser(), conf.getPopPasswd());
		}

		this.setFrom(conf.getSmtpMailAddress());

		if(!conf.getForwardReplyTo().isEmpty()){
			for (String addr : conf.getForwardReplyTo()) {
				this.addReplyTo(addr);
			}
		}

		List<String> list = conf.getForwardTo();
		for (String addr : list) {
			this.addTo(addr);
		}

		list = conf.getForwardCc();
		for (String addr : list) {
			this.addCc(addr);
		}

		list = conf.getForwardBcc();
		for (String addr : list) {
			this.addBcc(addr);
		}

		try{
			// ヘッダを取得
			
			// XXX
			// 無限ループ抑止のため X-Mailer をチェックした方がいいか
			
			// Subject:
			smmSubject = smm.getSubject();
			// Date:
			try{
				smmDate = smm.getSentDate();
			}catch(Exception ee){
				// Dateヘッダ不正文字列混入（大抵spam）
				smmDate = null;
			}
			// XXX ImodeNetClient.java getMail() アドレス帳との連携は未
			// From:
			smmFromAddr = (InternetAddress) smm.getFrom()[0];
			headerInfo.append(" From:    ").append(smmFromAddr.toUnicodeString()).append("\r\n");
			// ReplyTo:
			Address[] addrsReplyTo = smm.getReplyTo();
			if (addrsReplyTo != null){
				for (int i = 0; i < addrsReplyTo.length; i++) {
					smmReplyToAddrList.add((InternetAddress) addrsReplyTo[i]);
				}
			}
			// To:
			Address[] addrsTo = smm.getRecipients(Message.RecipientType.TO);
			String label = " To:";
			boolean bccLabel = true;
			if (addrsTo != null){
				for (int i = 0; i < addrsTo.length; i++) {
					InternetAddress addr = (InternetAddress) addrsTo[i];
					smmToAddrList.add(addr);
					headerInfo.append(label + "      ").append(addr.toUnicodeString()).append("\r\n");
					label="    ";
					if (bccLabel && addr.getAddress().equalsIgnoreCase(conf.getSpmodeMailAddr())){
						bccLabel = false;
					}
				}
			}
			// Cc:
			Address[] addrsCc = smm.getRecipients(Message.RecipientType.CC);
			label = " Cc:";
			if (addrsCc != null){
				for (int i = 0; i < addrsCc.length; i++) {
					InternetAddress addr = (InternetAddress) addrsCc[i];
					smmCcAddrList.add(addr);
					headerInfo.append(label + "      ").append(addr.toUnicodeString()).append("\r\n");
					label="    ";
					if (bccLabel && addr.getAddress().equalsIgnoreCase(conf.getSpmodeMailAddr())){
						bccLabel = false;
					}
				}
			}
			// その他ヘッダ情報
			if(bccLabel){
				headerInfo.append(" Bcc:     ").append(conf.getSpmodeMailAddr()).append("\r\n");
			}
			SimpleDateFormat df = new SimpleDateFormat("yyyy/MM/dd (EEE) HH:mm:ss");
			if(smmDate!=null){
				headerInfo.append(" Date:    ").append(df.format(smmDate)).append("\r\n");
			}else{
				headerInfo.append(" Date:    (取得エラー)\r\n");
			}
			if(conf.isSubjectEmojiReplace()){
				headerInfo.append(" Subject: ").append(EmojiUtil.replaceToLabel(smmSubject)).append("\r\n");
			}else{
				headerInfo.append(" Subject: ").append(smmSubject).append("\r\n");
			}
			if(conf.getConfigId() == 1){
				log.info("spモードメールを転送\n"+headerInfo);
			}

			// メールを分解
			parsePart(smm);
		} catch (MessagingException e) {
			
		}

		// 文字列置換
		this.plainBody = this.strConv.convert(this.plainBody);
		this.htmlBody = this.strConv.convert(this.htmlBody);

		Config.BodyEmojiReplace emojiReplace = conf.getBodyEmojiReplace();
		if(emojiReplace==Config.BodyEmojiReplace.DontReplace){
			this.setBodyDontReplace();
		}else if(emojiReplace==Config.BodyEmojiReplace.ToInlineImage){
			this.setBodyToInlineImage();
		}else if(emojiReplace==Config.BodyEmojiReplace.ToWebLink){
			this.setBodyToWebLink();
		}else if(emojiReplace==Config.BodyEmojiReplace.ToLabel){
			this.setBodyToLabel();
		}else if(emojiReplace==Config.BodyEmojiReplace.ToSubjectTable){
			this.plainBody = this.subjectCharConv.convert(this.plainBody);
			this.htmlBody = this.subjectCharConv.convert(this.htmlBody);
			this.setBodyDontReplace();
		}
	}

	private void parsePart(Part p) throws MessagingException {
		if (this.plainBody.isEmpty() && p.isMimeType("text/plain")) {
			this.hasPlain = true;
			try {
				this.plainBody = p.getContent().toString();
			} catch (IOException io) {
				this.plainBody = "";
			}
		} else if (this.htmlBody.isEmpty() && p.isMimeType("text/html")) {
			this.decomeFlg = true;
			try {
				this.htmlBody = p.getContent().toString();
			} catch (IOException IO) {
				this.htmlBody = "";
			}
		} else if (p.isMimeType("multipart/*")) {
			Multipart mp;
			try {
				mp = (Multipart)p.getContent();
			} catch (IOException e) {
				return;
			}
			for (int i = 0; i < mp.getCount(); i++) {
				parsePart(mp.getBodyPart(i));
			}
		} else {
			String disposition = p.getDisposition();
			getContainer().addBodyPart((BodyPart)p);
			// information
			log.info("添付ファイル追加");
			if (disposition != null && disposition.equals(Part.INLINE)) {
				// インライン添付ファイル追加処理
				log.info("Content-Type: "+p.getContentType());
				log.info("Content-Disposition: INLINE");
			} else if (disposition != null && disposition.equals(Part.ATTACHMENT)) {
				// 添付ファイル追加処理
				log.info("Content-Type: "+p.getContentType());
				log.info("Content-Disposition: ATTACHEMENT");
			} else {
				// その他（未考慮）
				log.info("Content-Type: "+p.getContentType());
			}
		}
	}

	/*
	 * 絵文字の置き換えは行わない
	 */
	private void setBodyDontReplace() throws EmailException{
		String html = this.htmlBody;
		String plain = this.plainBody;
		if(this.decomeFlg){
			// HTMLメール
			if (plain.isEmpty())
				plain = Util.html2text(html);
		}else{
			String fontfamily = conf.getMailFontFamily();
			if (fontfamily!=null){
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:\'"+fontfamily+"\';\">"+Util.easyEscapeHtml(plain)+"</pre></body>";
			}else{
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"+Util.easyEscapeHtml(plain)+"</pre></body>";
			}
		}
		this.setBodyDontReplace(plain, html);
	}
	private void setBodyDontReplace(String plainText, String html) throws EmailException{

		// html
		if(conf.isHeaderToBody()){
			if(html.matches(".*<body[^>]*>.*")){
				html = html.replaceAll("(<body[^>]*>)", "$1"+Util.getHeaderInfo(this.headerInfo.toString(), true, conf));
			}else{
				html = "<body>" + Util.getHeaderInfo(this.headerInfo.toString(), true, conf) + html + "</body>";
			}
		}

		// テキスト
		if(conf.isHeaderToBody()){
			plainText = Util.getHeaderInfo(this.headerInfo.toString(), false, conf)+plainText;
		}

		html = "<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset="+this.charset+"\"></head>"+html+"</html>";
		try{
			this.setHtmlMsg(html);
			if(conf.isMailAlternative()||this.hasPlain){
				this.setTextMsg(plainText);
			}
		}catch (Exception e) {
			throw new EmailException(e);
		}
	}
	private void setBodyToInlineImage() throws EmailException{
		String html = this.htmlBody;
		String plain = this.plainBody;
		String va;
		String px;
		if(this.decomeFlg){
			// HTMLメール
			if (plain.isEmpty())
				plain = Util.html2text(EmojiUtil.replaceToLabel(html));
			else
				plain = EmojiUtil.replaceToLabel(plain);
			va = conf.getBodyEmojiVAlignHtml();
			px = conf.getBodyEmojiSizeHtml();
		}else{
			String fontfamily = conf.getMailFontFamily();
			if(fontfamily!=null){
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:\'"+fontfamily+"\';\">"+Util.easyEscapeHtml(plain)+"</pre></body>";
			}else{
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"+Util.easyEscapeHtml(plain)+"</pre></body>";
			}
			plain = EmojiUtil.replaceToLabel(plain);
			va = conf.getBodyEmojiVAlign();
			px = conf.getBodyEmojiSize();
			
			html = "<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset="+this.charset+"\"></head>"+html+"</html>";
		}
		
		Map<URL, String> emojiToCid = new HashMap<URL, String>();
		StringBuilder buf = new StringBuilder();
		for(char c : html.toCharArray()){
			if(!EmojiUtil.isEmoji(c)){
				buf.append(c);
				continue;
			}
			try{
				URL emojiUrl = EmojiUtil.emojiToImageUrl(c);
				if(emojiUrl==null){
					buf.append(EmojiUtil.UnknownReplace);
				}else{
					String cid = emojiToCid.get(emojiUrl);
					if(cid==null){
						cid = this.embed(emojiUrl, "emoji"+((int)c));
						emojiToCid.put(emojiUrl, cid);
					}
					String wh = "";
					if(px!=null){
						wh = " width: "+px+"; height: "+px+";";
					}
					buf.append("<img src=\"cid:"+cid+"\" style=\"margin: 0pt 0.2ex; vertical-align: "+va+";"+wh+"\">");
				}
			}catch (Exception e) {
				log.warn("Emoji to inline image Error.",e);
				buf.append(EmojiUtil.UnknownReplace);
			}
		}
		this.setBodyDontReplace(plain,buf.toString());

	}
	private void setBodyToWebLink() throws EmailException{
		String html = this.htmlBody;
		String plain = this.plainBody;
		String va;
		String px;
		if(this.decomeFlg){
			// HTMLメール
			va = conf.getBodyEmojiVAlignHtml();
			px = conf.getBodyEmojiSizeHtml();
			if (plain.isEmpty())
				plain = Util.html2text(EmojiUtil.replaceToLabel(html));
			else
				plain = EmojiUtil.replaceToLabel(plain);
			html = EmojiUtil.replaceToWebLink(html, va, px);
		}else{
			String fontfamily = conf.getMailFontFamily();
			if(fontfamily!=null){
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:\'"+fontfamily+"\';\">"+Util.easyEscapeHtml(plain)+"</pre></body>";
			}else{
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"+Util.easyEscapeHtml(plain)+"</pre></body>";
			}
			va = conf.getBodyEmojiVAlign();
			px = conf.getBodyEmojiSize();
			html = EmojiUtil.replaceToWebLink(html, va, px);
			plain = EmojiUtil.replaceToLabel(plain);

			html = "<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset="+this.charset+"\"></head>"+html+"</html>";
		}
		this.setBodyDontReplace(plain, html);
	}
	private void setBodyToLabel() throws EmailException{
		String html = this.htmlBody;
		String plain = this.plainBody;
		if(this.decomeFlg){
			// HTMLメール
			html = EmojiUtil.replaceToLabel(html);
			if (plain.isEmpty())
				plain = Util.html2text(html);
			else
				plain = EmojiUtil.replaceToLabel(plain);
		}else{
			String fontfamily = conf.getMailFontFamily();
			if(fontfamily!=null){
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:\'"+fontfamily+"\';\">"+Util.easyEscapeHtml(plain)+"</pre></body>";
			}else{
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"+Util.easyEscapeHtml(plain)+"</pre></body>";
			}
			html = EmojiUtil.replaceToLabel(html);
			plain = EmojiUtil.replaceToLabel(plain);

			html = "<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset="+this.charset+"\"></head>"+html+"</html>";
		}
		this.setBodyDontReplace(plain,html);
	}

	@Override
	public void buildMimeMessage() throws EmailException {
		super.buildMimeMessage();
		MimeMessage msg = this.getMimeMessage();
		try{
			msg.setHeader("X-Mailer", ServerMain.Version);

			if(!this.conf.isRewriteAddress()){
				// もとのSpmodeメールの送信元送信先に置き換える
				msg.setHeader("Resent-From",this.conf.getSmtpMailAddress());
				if(!this.conf.getForwardTo().isEmpty()){
					msg.setHeader("Resent-To", StringUtils.join(this.conf.getForwardTo(), ","));
				}
				if(!this.conf.getForwardCc().isEmpty()){
					msg.setHeader("Resent-Cc", StringUtils.join(this.conf.getForwardCc(), ","));
				}
				if(!this.conf.getForwardBcc().isEmpty()){
					msg.setHeader("Resent-Bcc", StringUtils.join(this.conf.getForwardBcc(), ","));
				}
				SimpleDateFormat df = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z (z)",Locale.US);
				msg.setHeader("Resent-Date", df.format(new Date()));
				msg.setHeader("Date", df.format(this.smmDate));

				msg.removeHeader("To");
				msg.removeHeader("Cc");
				msg.removeHeader("Bcc");

				// 自アドレスが非表示になるのはimode特有のため forward.hidemyaddr は無効
				/*
				List<InternetAddress> tolist = new ArrayList<InternetAddress>();
				List<InternetAddress> cclist = new ArrayList<InternetAddress>();

				boolean useMyAddress=false;
				if(this.imm.getFolderId()!=SpmodeNetClient.FolderIdSent){
					if(this.conf.isHideMyaddr()){
						if(this.imm.getToAddrList().size()==0){
							useMyAddress=true;
						}
					}else{
						useMyAddress=true;
					}
				}
				if(useMyAddress){
					switch(this.imm.getRecvType()){
					case SpmodeMail.RECV_TYPE_TO:
						tolist.add(this.imm.getMyInternetAddress());
						break;
					case SpmodeMail.RECV_TYPE_CC:
						cclist.add(this.imm.getMyInternetAddress());
						break;
					case SpmodeMail.RECV_TYPE_BCC:
						break;
					}
				}
				tolist.addAll(this.imm.getToAddrList());
				cclist.addAll(this.imm.getCcAddrList());
				*/

				if(smmToAddrList.size()>0){
					msg.setHeader("To", InternetAddress.toString(smmToAddrList.toArray(new InternetAddress[0])));
				}

				if(smmCcAddrList.size()>0){
					msg.setHeader("Cc", InternetAddress.toString(smmCcAddrList.toArray(new InternetAddress[0])));
				}
				if(smmReplyToAddrList.size()>0){
					msg.setHeader("Reply-To", InternetAddress.toString(smmReplyToAddrList.toArray(new InternetAddress[0])));
				}

				msg.setFrom(smmFromAddr);
			}

			String subject = null;
			subject = conf.getSubjectAppendPrefix()+smmSubject+conf.getSubjectAppendSuffix();
			if(conf.isSubjectEmojiReplace()){
				subject = EmojiUtil.replaceToLabel(subject);
			}

			if(this.goomojiSubjectCharConv != null){
				String goomojiSubject = this.goomojiSubjectCharConv.convert(subject);
				msg.setHeader("X-Goomoji-Source", "docomo_ne_jp");
				msg.setHeader("X-Goomoji-Subject", Util.encodeGoomojiSubject(goomojiSubject));
			}

			subject = this.subjectCharConv.convert(subject);
			msg.setSubject(MimeUtility.encodeText(subject,this.charset,"B"));

			if(this.conf.getContentTransferEncoding()!=null){
				msg.setHeader("Content-Transfer-Encoding", this.conf.getContentTransferEncoding());
			}

		}catch (Exception e) {
			log.warn(e);
		}
	}

	@Override
	protected MimeMessage createMimeMessage(Session aSession) {
		List<InternetAddress> recipients = new ArrayList<InternetAddress>();
		List<String> list = conf.getForwardTo();
		for (String addr : list) {
			try{
				recipients.add(new InternetAddress(addr));
			}catch (Exception e) {
				log.warn("ForwardTo error "+addr,e);
			}
		}
		list = conf.getForwardCc();
		for (String addr : list) {
			try{
				recipients.add(new InternetAddress(addr));
			}catch (Exception e) {
				log.warn("ForwardCc error "+addr,e);
			}
		}
		list = conf.getForwardBcc();
		for (String addr : list) {
			try{
				recipients.add(new InternetAddress(addr));
			}catch (Exception e) {
				log.warn("ForwardBcc error "+addr,e);
			}
		}
		String from = this.conf.getSmtpMailAddress();
		try{
			return new MyMimeMessage(aSession, new InternetAddress(from), recipients);
		}catch (Exception e) {
			log.warn("From error "+from,e);
			return null;
		}
	}

	@Override
	public MyHtmlEmail setHtmlMsg(String html) throws EmailException {
		html = Util.replaceUnicodeMapping(html);
		html += "\n";
		try{
			html = new String(html.getBytes(this.charset));
		}catch (Exception e) {
			log.error("setHtmlMsg",e);
		}
		return super.setHtmlMsg(html);
	}

	@Override
	public Email setSubject(String subject) {
		return super.setSubject(Util.replaceUnicodeMapping(subject));
	}

	public static void setSubjectCharConv(Map<Config, CharacterConverter> subjectCharConvMap) {
		SpmodeForwardMail.subjectCharConvMap = subjectCharConvMap;
	}

	public static void setGoomojiSubjectCharConv(Map<Config, CharacterConverter> goomojiSubjectCharConvMap) {
		SpmodeForwardMail.goomojiSubjectCharConvMap = goomojiSubjectCharConvMap;
	}

	public static void setStrConv(Map<Config, StringConverter> strConvMap) {
		SpmodeForwardMail.strConvMap = strConvMap;
	}
}
