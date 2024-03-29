/*
 * imoten - i mode.net mail tensou(forward)
 *
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

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.activation.DataHandler;
import javax.mail.BodyPart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.mail.ByteArrayDataSource;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;

public class ImodeForwardMail extends MyHtmlEmail {
	private static final Log log = LogFactory.getLog(ImodeForwardMail.class);
	private ImodeMail imm;
	private Config conf;
	private CharacterConverter subjectCharConv = null;
	private CharacterConverter goomojiSubjectCharConv = null;
	private StringConverter strConv = null;
	private static Map<Config, CharacterConverter> subjectCharConvMap = null;
	private static Map<Config, CharacterConverter> goomojiSubjectCharConvMap = null;
	private static Map<Config, StringConverter> strConvMap = null;

	public ImodeForwardMail(ImodeMail imm, Config conf) throws EmailException{
		this.imm = imm;
		this.conf = conf;

		this.setDebug(conf.isMailDebugEnable());
		this.setCharset(this.conf.getMailEncode());
		this.setContentTransferEncoding(this.conf.getContentTransferEncoding());

		if(ImodeForwardMail.subjectCharConvMap!=null
				&& ImodeForwardMail.subjectCharConvMap.containsKey(conf)){
			this.subjectCharConv = ImodeForwardMail.subjectCharConvMap.get(conf);
		}else{
			this.subjectCharConv = new CharacterConverter();
		}
		if(ImodeForwardMail.goomojiSubjectCharConvMap!=null
				&& ImodeForwardMail.goomojiSubjectCharConvMap.containsKey(conf)){
			this.goomojiSubjectCharConv = ImodeForwardMail.goomojiSubjectCharConvMap.get(conf);
		}else{
			this.goomojiSubjectCharConv = new CharacterConverter();
		}
		if(ImodeForwardMail.strConvMap!=null
				&& ImodeForwardMail.strConvMap.containsKey(conf)){
			this.strConv = ImodeForwardMail.strConvMap.get(conf);
		}else{
			this.strConv = new StringConverter();
		}

		// SMTP Server
		this.setHostName(conf.getSmtpServer());
		this.setSmtpPort(conf.getSmtpPort());
		this.setSocketConnectionTimeout(conf.getSmtpConnectTimeoutSec()*1000);
		this.setSocketTimeout(conf.getSmtpTimeoutSec()*1000);
		this.setTLS(conf.isSmtpTls());
		this.setSSL(conf.isSmtpSsl());

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

		String subject = null;
		if(imm.getFolderId()==ImodeNetClient.FolderIdSent){
			subject = conf.getSentSubjectAppendPrefix()+imm.getSubject()+conf.getSentSubjectAppendSuffix();
		}else{
			subject = conf.getSubjectAppendPrefix()+imm.getSubject()+conf.getSubjectAppendSuffix();
		}

		if(conf.isSubjectEmojiReplace()){
			this.setSubject(EmojiUtil.replaceToLabel(subject));
		}else{
			this.setSubject(subject);
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

		// 文字列置換
		if(this.imm.getFolderId()!=ImodeNetClient.FolderIdSent&&true){
			this.imm.setBody(this.strConv.convert(this.imm.getBody()));
		}

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
			this.imm.setBody(this.subjectCharConv.convert(this.imm.getBody()));
			this.setBodyDontReplace();
		}
		// 添付ファイル
		this.attacheFile();
		this.attacheInline();
	}

	/*
	 * デコメールで<img src=".....">でインライン表示させるが、
	 * imode.netのhtmlでは[cid:]という部分が抜けているので付加する。
	 */
	private static String cidAddedBody(String html, List<AttachedFile> inlines){
		for (AttachedFile f : inlines) {
			html = StringUtils.replace(html, f.getId(), "cid:"+f.getId());
		}
		return html;
	}

	/*
	 * 絵文字の置き換えは行わない
	 */
	private void setBodyDontReplace() throws EmailException{
		String html = this.imm.getBody();
		String plain = this.imm.getBody();
		if(this.imm.isDecomeFlg()){
			// HTMLメール
			plain = Util.html2text(plain);
		}else{
			String fontfamily = conf.getMailFontFamily();
			if (fontfamily!=null){
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:\'"+fontfamily+"\';\">"+Util.easyEscapeHtml(html)+"</pre></body>";
			}else{
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"+Util.easyEscapeHtml(html)+"</pre></body>";
			}
		}
		this.setBodyDontReplace(plain, html,this.imm.getInlineFileList());
	}
	private void setBodyDontReplace(String plainText, String html, List<AttachedFile> inlineFiles) throws EmailException{

		// htmlメール
		html = cidAddedBody(html,inlineFiles);
		if(conf.isHeaderToBody()){
			html = html.replaceAll("(<body[^>]*>)", "$1"+Util.getHeaderInfo(this.imm, true, this.conf.isSubjectEmojiReplace(), conf));
		}

		// テキスト
		if(conf.isHeaderToBody()){
			plainText = Util.getHeaderInfo(this.imm, false, this.conf.isSubjectEmojiReplace(), conf)+plainText;
		}

		html = "<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset="+this.charset+"\"></head>"+html+"</html>";
		try{
			this.setHtmlMsg(html);
			if(conf.isMailAlternative()){
				this.setTextMsg(plainText);
			}
		}catch (Exception e) {
			throw new EmailException(e);
		}
	}
	private void setBodyToInlineImage() throws EmailException{
		String html = this.imm.getBody();
		String plain = this.imm.getBody();
		String va;
		String px;
		if(this.imm.isDecomeFlg()){
			// HTMLメール
			plain = Util.html2text(EmojiUtil.replaceToLabel(plain));
			va = conf.getBodyEmojiVAlignHtml();
			px = conf.getBodyEmojiSizeHtml();
		}else{
			String fontfamily = conf.getMailFontFamily();
			if(fontfamily!=null){
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:\'"+fontfamily+"\';\">"+Util.easyEscapeHtml(html)+"</pre></body>";
			}else{
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"+Util.easyEscapeHtml(html)+"</pre></body>";
			}
			plain = EmojiUtil.replaceToLabel(plain);
			va = conf.getBodyEmojiVAlign();
			px = conf.getBodyEmojiSize();
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
		this.setBodyDontReplace(plain,buf.toString(),this.imm.getInlineFileList());

	}
	private void setBodyToWebLink() throws EmailException{
		String html = this.imm.getBody();
		String plain = this.imm.getBody();
		String va;
		String px;
		if(this.imm.isDecomeFlg()){
			// HTMLメール
			va = conf.getBodyEmojiVAlignHtml();
			px = conf.getBodyEmojiSizeHtml();
			html = EmojiUtil.replaceToWebLink(html, va, px);
			plain = Util.html2text(EmojiUtil.replaceToLabel(plain));
		}else{
			String fontfamily = conf.getMailFontFamily();
			if(fontfamily!=null){
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:\'"+fontfamily+"\';\">"+Util.easyEscapeHtml(html)+"</pre></body>";
			}else{
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"+Util.easyEscapeHtml(html)+"</pre></body>";
			}
			va = conf.getBodyEmojiVAlign();
			px = conf.getBodyEmojiSize();
			html = EmojiUtil.replaceToWebLink(html, va, px);
			plain = EmojiUtil.replaceToLabel(plain);
		}
		this.setBodyDontReplace(plain, html, this.imm.getInlineFileList());
	}
	private void setBodyToLabel() throws EmailException{
		String html = this.imm.getBody();
		String plain = this.imm.getBody();
		if(this.imm.isDecomeFlg()){
			// HTMLメール
			html = EmojiUtil.replaceToLabel(html);
			plain = Util.html2text(EmojiUtil.replaceToLabel(plain));
		}else{
			String fontfamily = conf.getMailFontFamily();
			if(fontfamily!=null){
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:\'"+fontfamily+"\';\">"+Util.easyEscapeHtml(html)+"</pre></body>";
			}else{
				html = "<body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">"+Util.easyEscapeHtml(html)+"</pre></body>";
			}
			html = EmojiUtil.replaceToLabel(html);
			plain = EmojiUtil.replaceToLabel(plain);
		}
		this.setBodyDontReplace(plain,html,this.imm.getInlineFileList());
	}

	/*
	 * 添付ファイルを追加する
	 */
	private void attacheFile() throws EmailException{
		try{
			List<AttachedFile> files = this.imm.getAttachFileList();
			for (AttachedFile f : files) {
				BodyPart part = createBodyPart();
				part.setDataHandler(new DataHandler(new ByteArrayDataSource(f.getData(),f.getContentType())));
				Util.setFileName(part, f.getFilename(), this.charset, null);
				part.setDisposition(BodyPart.ATTACHMENT);
				getContainer().addBodyPart(part);
			}
		}catch (Exception e) {
			throw new EmailException(e);
		}
	}
	/*
	 * 添付ファイルをInlineで追加する
	 */
	private void attacheInline() throws EmailException{
		try{
			List<AttachedFile> files = this.imm.getInlineFileList();
			for (AttachedFile f : files) {
				this.embed(new ByteArrayDataSource(f.getData(),f.getContentType()), f.getFilename(), this.charset, f.getId());
			}
		}catch (Exception e) {
			throw new EmailException(e);
		}

	}


	@Override
	public void buildMimeMessage() throws EmailException {
		super.buildMimeMessage();
		MimeMessage msg = this.getMimeMessage();
		try{
			msg.setHeader("X-Mailer", ServerMain.Version);

			if(!this.conf.isRewriteAddress()){
				// もとのimodeメールの送信元送信先に置き換える
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
				msg.setHeader("Date", df.format(this.imm.getTimeDate()));

				msg.removeHeader("To");
				msg.removeHeader("Cc");
				msg.removeHeader("Bcc");

				List<InternetAddress> tolist = new ArrayList<InternetAddress>();
				List<InternetAddress> cclist = new ArrayList<InternetAddress>();

				boolean useMyAddress=false;
				if(this.imm.getFolderId()!=ImodeNetClient.FolderIdSent){
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
					case ImodeMail.RECV_TYPE_TO:
						tolist.add(this.imm.getMyInternetAddress());
						break;
					case ImodeMail.RECV_TYPE_CC:
						cclist.add(this.imm.getMyInternetAddress());
						break;
					case ImodeMail.RECV_TYPE_BCC:
						break;
					}
				}
				tolist.addAll(this.imm.getToAddrList());
				cclist.addAll(this.imm.getCcAddrList());

				msg.setHeader("To", InternetAddress.toString(tolist.toArray(new InternetAddress[0])));

				if(this.imm.getCcAddrList().size()>0){
					msg.setHeader("Cc", InternetAddress.toString(cclist.toArray(new InternetAddress[0])));
				}

				msg.setFrom(this.imm.getFromAddr());
			}

			String subject = null;
			if(imm.getFolderId()==ImodeNetClient.FolderIdSent){
				subject = conf.getSentSubjectAppendPrefix()+imm.getSubject()+conf.getSentSubjectAppendSuffix();
			}else{
				subject = conf.getSubjectAppendPrefix()+imm.getSubject()+conf.getSubjectAppendSuffix();
			}
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
		ImodeForwardMail.subjectCharConvMap = subjectCharConvMap;
	}

	public static void setGoomojiSubjectCharConv(Map<Config, CharacterConverter> goomojiSubjectCharConvMap) {
		ImodeForwardMail.goomojiSubjectCharConvMap = goomojiSubjectCharConvMap;
	}

	public static void setStrConv(Map<Config, StringConverter> strConvMap) {
		ImodeForwardMail.strConvMap = strConvMap;
	}
}
