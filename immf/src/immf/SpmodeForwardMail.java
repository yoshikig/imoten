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
import java.lang.IllegalStateException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.activation.DataHandler;
import javax.activation.URLDataSource;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.ContentType;
import javax.mail.internet.HeaderTokenizer;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;

import org.apache.commons.codec.binary.Base64;
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
	private String keyPlainBody = "";
	private String keyHtmlBody = "";
	private String optionPlainMsg = "";
	private String optionHtmlMsg = "";
	private String smmSubject;
	private Date smmDate;
	private InternetAddress smmFromAddr;
	private List<InternetAddress> smmReplyToAddrList = new ArrayList<InternetAddress>();
	private List<InternetAddress> smmToAddrList = new ArrayList<InternetAddress>();
	private List<InternetAddress> smmCcAddrList = new ArrayList<InternetAddress>();
	private boolean decomeFlg = false;
	private boolean hasPlain = false;
	private boolean isSent = false;
	private Config conf;
	private CharacterConverter subjectCharConv = null;
	private CharacterConverter goomojiSubjectCharConv = null;
	private StringConverter strConv = null;
	private static Map<Config, CharacterConverter> subjectCharConvMap = null;
	private static Map<Config, CharacterConverter> goomojiSubjectCharConvMap = null;
	private static Map<Config, StringConverter> strConvMap = null;
	private StringBuilder headerInfo = new StringBuilder();
	private Map<String, String> bodyMap = new HashMap<String, String>();
	private Map<URL, String> emojiToCid = new HashMap<URL, String>();
	private Map<URL, String> emojiToCode = new HashMap<URL, String>();
	private MimeMultipart rootMultipart = null;
	private List<BodyPart> attachedParts = new ArrayList<BodyPart>();
	private boolean doDocomoCharConv = false;
	private static CharacterConverter docomoCharConv = null;

	private AddressBook addressBook;
	private String mailAddrCharset = "ISO-2022-jP";

	public SpmodeForwardMail(Message sm, Config conf, AddressBook addressBook) throws EmailException, IllegalStateException{
		this.smm = sm;
		this.conf = conf;
		this.addressBook = addressBook;

		this.setDebug(conf.isMailDebugEnable());
		this.setCharset(this.conf.getMailEncode());
		this.mailAddrCharset = this.charset;
		this.setContentTransferEncoding(this.conf.getContentTransferEncoding());

		/*
		 * 受信メールがUnicode6.0の絵文字に変換されて届くことから、以下を条件にUnicode絵文字を
		 * ドコモ絵文字に変換した上で処理を継続する。
		 *  subject … emojireplace.subject=true または forward.subject.charconvfile=設定なし
		 *  body … emojireplace.body=noneではない
		 * 
		 * spモードメール(pop3)とimap2.spmode.ne.jpはUnicode6.0絵文字に変換されるが、
		 * imap.spmode.ne.jpはドコモ側での変換はされずにSJIS、UTF-8混在でメールが届く。
		 * 本処理はメールのcharsetがUTF-8の時のみ実行する。(doDocomoCharConv)
		 * 
		 * さらに、docomoCharConvSubjectはsubjectの変換を行うかどうかの判定フラグ
		 */
		boolean docomoCharConvSubject = false;
		
		if(SpmodeForwardMail.subjectCharConvMap!=null
				&& SpmodeForwardMail.subjectCharConvMap.containsKey(conf)){
			this.subjectCharConv = SpmodeForwardMail.subjectCharConvMap.get(conf);
			docomoCharConvSubject = true;
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
		if(conf.isSubjectEmojiReplace()){
			docomoCharConvSubject = true;
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
			if(smm.getHeader(SpmodeImapReader.sentHeader)!=null){
				isSent = true;
			}
			
			// Subject:
			smmSubject = smm.getSubject();
			
			/*
			 * XXX
			 * ドコモが送ってくるメールなど、Unicode1文字が分割されてBase64エンコードされた件名の復号。
			 * getSubject()の延長で実行される decodeText() が  0xfffd を作り出すのでこれを判定に
			 * 用いているが、判定条件としては不十分かもしれない。
			 * HeaderTokenizer の使い道が違う気がする。
			 */
			boolean subjectWorkaround = false;
			if (smmSubject != null) {
				for (int i = 0; i < smmSubject.length(); ) {
					int cp = smmSubject.codePointAt(i);
					if (cp == 0xfffd) {
						log.warn("MimeUtility.decodeText()エラー発生。workaroundコードを実行します。");
						subjectWorkaround = true;
						break;
					}
					i += Character.charCount(cp);
				}
			}
			if (subjectWorkaround) {
				String[] h = smm.getHeader("Subject");
				if (h == null || h.length < 1) {
					smmSubject = "";
				} else {
					HeaderTokenizer tokenizer =
						new HeaderTokenizer(h[0], HeaderTokenizer.MIME, true);
					HeaderTokenizer.Token token;
					ByteBuffer bb = ByteBuffer.allocate(1024);
					String v;

					log.info("Subject decoding start. (workaround)");
					try {
						while (true) {
							// =
							token = tokenizer.next();
							if (token.getType() == HeaderTokenizer.Token.EOF) break;
							if (token.getType() == ';') break;
							if (token.getType() != '=') {
								throw new Exception("not MIME encoding : " + token.getValue());
							}

							// ?
							token = tokenizer.next();
							if (token.getType() != '?') {
								throw new Exception("not MIME encoding : " + token.getValue());
							}

							// utf-8
							token = tokenizer.next();
							if (!token.getValue().equalsIgnoreCase("UTF-8")) {
								throw new Exception("not UTF-8 string : " + token.getValue());
							}

							// ?
							token = tokenizer.next();
							if (token.getType() != '?') {
								throw new Exception("not MIME string : " + token.getValue());
							}

							// B
							token = tokenizer.next();
							if (!token.getValue().equalsIgnoreCase("B")) {
								throw new Exception("not Base64 encoding : " + token.getValue());
							}

							// ?
							token = tokenizer.next();
							if (token.getType() != '?') {
								throw new Exception("not MIME encoding : " + token.getValue());
							}

							// Base64 part
							StringBuffer sb = new StringBuffer();
							token = tokenizer.next();
							for (;token.getType() != '?'; token = tokenizer.next()){
								if (token.getType() == HeaderTokenizer.Token.EOF) {
									throw new Exception("unexpected EOF");
								}
								v = token.getValue();
								sb.append(v);
							}
							String s = new String(sb);
							log.info("base64: " + s);
							bb.put(Base64.decodeBase64(s.getBytes()));

							// =
							token = tokenizer.next();
							if (token.getType() != '=') {
								throw new Exception("not MIME string : " + token.getValue());
							}
						}
					} catch (Exception eee){
						log.error("NO GOOD",eee);
					}
					bb.limit(bb.position());
					bb.position(0);
					byte[] ba = new byte[bb.limit()];
					bb.get(ba);
					smmSubject = new String(ba);

					log.info("Subject decoding done: " + smmSubject);
				}
			}
			if (smmSubject==null){
				smmSubject = "";
			}
			// Date:
			try{
				smmDate = smm.getSentDate();
			}catch(Exception ee){
				// Dateヘッダ不正文字列混入（大抵spam）
				smmDate = null;
			}
			if (smmDate==null){
				/*
				 * getSentDate()がnullを設定する場合とcatchに拾われてnullになる場合あり
				 * getSentDate()はDate終端の()内が予期しない形式などの場合にnullを返す模様
				 * 国内某銀行がこういうメールを送るようなのでこれは救っておく
				 */
				String date[] = smm.getHeader("Date");
				if (date.length > 0) {
					// Date: 終端の () は無視して内容を取り込む
					SimpleDateFormat df = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z",Locale.US);
					try {
						smmDate = df.parse(date[0]);
					} catch (Exception ee) {
						log.error("Dateヘッダ取得エラー",ee);
						smmDate = null;
					}
				} else {
					smmDate = null;
				}
			}

			// From:
			smmFromAddr = this.addressBook.getInternetAddress((InternetAddress) smm.getFrom()[0],this.mailAddrCharset);
			headerInfo.append(" From:    ").append(smmFromAddr.toUnicodeString()).append("\r\n");
			// ReplyTo:
			if(smm.getHeader("Reply-To")!=null){
				for (String addr : smm.getHeader("Reply-To")){
					smmReplyToAddrList.add(this.addressBook.getInternetAddress(addr,this.mailAddrCharset));
				}
			}
			// To:
			Address[] addrsTo = smm.getRecipients(Message.RecipientType.TO);
			String label = " To:";
			boolean bccLabel = true;
			if(isSent){
				bccLabel = false;
			}
			if (addrsTo != null){
				for (int i = 0; i < addrsTo.length; i++) {
					InternetAddress addr = this.addressBook.getInternetAddress((InternetAddress) addrsTo[i],this.mailAddrCharset);
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
					InternetAddress addr = this.addressBook.getInternetAddress((InternetAddress) addrsCc[i],this.mailAddrCharset);
					smmCcAddrList.add(addr);
					headerInfo.append(label + "      ").append(addr.toUnicodeString()).append("\r\n");
					label="    ";
					if (bccLabel && addr.getAddress().equalsIgnoreCase(conf.getSpmodeMailAddr())){
						bccLabel = false;
					}
				}
			}
			// Bcc:
			if(bccLabel){
				headerInfo.append(" Bcc:     ").append(conf.getSpmodeMailAddr()).append("\r\n");
			}

			// テキストパートを取得
			parseTextPart(smm);
		} catch (MessagingException e) {
			log.error(e);
		}

		// UTF-8 で届いたメッセージのUnicode絵文字からドコモ絵文字への変換
		if(this.doDocomoCharConv && docomoCharConvSubject){
			smmSubject = SpmodeForwardMail.docomoCharConv.convert(smmSubject);
		}

		// その他ヘッダ情報
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
		if(isSent){
			log.info("spモードメール(送信BOX)を転送\n"+headerInfo);
		}else if(conf.getConfigId() == 1){
			log.info("spモードメールを転送\n"+headerInfo);
		}
		
		// 未使用。本文末尾にメッセージを追加する際に使用。parseTextPart()内でoptionPlainMsgの設定を想定
		this.plainBody += this.optionPlainMsg;
		this.htmlBody += this.optionHtmlMsg;
		
		// 文字列置換
		this.plainBody = this.strConv.convert(this.plainBody);
		this.htmlBody = this.strConv.convert(this.htmlBody);

		// UTF-8 で届いたメッセージのUnicode絵文字からドコモ絵文字への変換
		Config.BodyEmojiReplace emojiReplace = conf.getBodyEmojiReplace();
		if(this.doDocomoCharConv && emojiReplace!=Config.BodyEmojiReplace.DontReplace){
			this.plainBody = SpmodeForwardMail.docomoCharConv.convert(this.plainBody);
			this.htmlBody = SpmodeForwardMail.docomoCharConv.convert(this.htmlBody);
		}
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
		
		// メールを分解して新しいメッセージの組み立て
		if(conf.isForwardFixMultipartRelated()){
			log.info("forward.spmode.fixinlineattach:有効");
		}else{
			log.info("forward.spmode.fixinlineattach:無効");
		}
		try{
			parsePart(smm, getContainer());
			
			// インラインではない添付ファイルを拾って一番外側のパートに格納
			if(conf.isForwardFixMultipartRelated()){
				if(getSubtype(smm.getContentType()).equalsIgnoreCase("mixed")){
					rootMultipart = getContainer();
				}
				for(BodyPart p : attachedParts) {
					parsePart(p, rootMultipart);
				}
			}
			
			// XXX textとhtmlだけのメールはparsePartのmultipart処理でemoji追加ルートに入らない
			// それを拾うためのもの。
			if(emojiToCid.size()>0){
				log.info("未添付の絵文字追加");
				MimeMultipart newMimeMultipart = new MimeMultipart("related");
				Multipart mp = getContainer();
				
				for (int i = 0; i < mp.getCount(); i++) {
					BodyPart childBodyPart = mp.getBodyPart(i);
					log.info("move part: "+childBodyPart.getContentType());
					mp.removeBodyPart(childBodyPart);
					newMimeMultipart.addBodyPart(childBodyPart);
				}

				addEmojiPart(newMimeMultipart);

				MimeBodyPart newPart = new MimeBodyPart();
				newPart.setContent(newMimeMultipart);
				mp.addBodyPart(newPart);
			}
		}catch (MessagingException e){
			log.error(e);
		}
	}

	private void parseTextPart(Part p) throws MessagingException{
		if (this.plainBody.isEmpty() && p.isMimeType("text/plain")) {
			this.hasPlain = true;
			try {
				this.plainBody = p.getContent().toString();
				this.keyPlainBody = this.plainBody;
				String charset = (new ContentType(p.getContentType().toLowerCase())).getParameter("charset");
				if(charset.equals("utf-8")){
					this.doDocomoCharConv = true;
				}
			} catch (IOException io) {
				this.plainBody = "";
			}
		} else if (this.htmlBody.isEmpty() && p.isMimeType("text/html")) {
			this.decomeFlg = true;
			try {
				this.htmlBody = p.getContent().toString();
				this.keyHtmlBody = this.htmlBody;
				String charset = (new ContentType(p.getContentType().toLowerCase())).getParameter("charset");
				if(charset.equals("utf-8")){
					this.doDocomoCharConv = true;
				}
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
				parseTextPart(mp.getBodyPart(i));
			}
		}
	}
	private boolean parsePart(Part p, MimeMultipart parentPart) throws MessagingException{
		boolean isInlineImage = false;
		log.info("parsing: "+p.getContentType());
		if (p.isMimeType("text/plain")) {
			String text = ""; 
			try {
				text = p.getContent().toString();
			} catch (IOException io) {
				text = "";
			}
			String emojitext = bodyMap.get(text);
			if (emojitext==null){
				// 本文ではない添付ファイルの text/plain
				parentPart.addBodyPart((BodyPart)p);
				return false;
			}
				
			MimeBodyPart thisPart = new MimeBodyPart();
			thisPart.setText(emojitext, this.charset, "plain");
			thisPart.setHeader("Content-Transfer-Encoding", "base64");
			log.info("PLAIN Part["+emojitext+"]");
			
			if(!decomeFlg){
				// 元メールがテキスト形式だった場合
				String html = bodyMap.get("<html>"+text);
				if(html!=null & !html.isEmpty()){
					MimeMultipart newMimeMultipartAlt = new MimeMultipart("alternative");
					MimeBodyPart newPartAlt = new MimeBodyPart();
					
					MimeBodyPart htmlPart = new MimeBodyPart();
					htmlPart.setText(html, this.charset, "html");
					htmlPart.setHeader("Content-Transfer-Encoding", "base64");
					log.info("HTML Part(created)["+html+"]");

					newMimeMultipartAlt.addBodyPart(thisPart);
					newMimeMultipartAlt.addBodyPart(htmlPart);
					newPartAlt.setContent(newMimeMultipartAlt);

					// 絵文字があった場合にマルチパートを作成して絵文字を挟み込む
					if(emojiToCid.size()>0){
						MimeMultipart newMimeMultipartRelated = new MimeMultipart("related");
						MimeBodyPart newPartRelated = new MimeBodyPart();
						newPartRelated.setContent(newMimeMultipartRelated);
						newMimeMultipartRelated.addBodyPart(newPartAlt);
						addEmojiPart(newMimeMultipartRelated);
						parentPart.addBodyPart(newPartRelated);
						
					}else{
						parentPart.addBodyPart(newPartAlt);
					}
				}
			}else{
				parentPart.addBodyPart(thisPart);
			}
			
		} else if (p.isMimeType("text/html")) {
			String text = "";
			try {
				text = p.getContent().toString();
			} catch (IOException IO) {
				text = "";
			}
			String emojihtml = bodyMap.get(text);
			if (emojihtml==null){
				// 本文ではない添付ファイルの text/html
				parentPart.addBodyPart((BodyPart)p);
				return false;
			}

			MimeBodyPart thisPart = new MimeBodyPart();
			thisPart.setText(emojihtml, this.charset, "html");
			thisPart.setHeader("Content-Transfer-Encoding", "base64");
			log.info("HTML Part["+emojihtml+"]");
			parentPart.addBodyPart(thisPart);
			
		} else if (p.isMimeType("multipart/*")) {
			String subtype = getSubtype(p.getContentType());
			MimeMultipart newMimeMultipart = new MimeMultipart(subtype);
			if(this.rootMultipart==null){
				this.rootMultipart = newMimeMultipart;
			}

			Multipart mp;
			boolean decome = false;
			boolean alternative = false;
			
			try{
				mp = (Multipart)p.getContent();
			} catch (IOException ie) {
				return false;
			}
			for (int i = 0; i < mp.getCount(); i++) {
				BodyPart childBodyPart = mp.getBodyPart(i);

				boolean inlineParsed = parsePart(childBodyPart,newMimeMultipart);
				
				// インライン添付ファイルがあったかどうか
				decome = inlineParsed || decome;

				// multipart/alternative があったかどうか
				String childContentType = childBodyPart.getContentType();
				String childSubType = getSubtype(childContentType);
				String childDisposition = childBodyPart.getDisposition();
				if(childSubType.equalsIgnoreCase("alternative")){
					alternative = true;
				}

				// インラインではない添付ファイルを related の外に出すための処理
				if(!inlineParsed && conf.isForwardFixMultipartRelated()){
					if(!childContentType.toLowerCase().startsWith("multipart/")
							&& ((childDisposition != null && childDisposition.equals(Part.ATTACHMENT))
									|| !childContentType.toLowerCase().startsWith("text/"))) {
						log.info("move part: "+childBodyPart.getFileName());
						newMimeMultipart.removeBodyPart(childBodyPart);
						attachedParts.add(childBodyPart);
					}
				}
			}

			// すでにあるインライン添付ファイルか multipart/alternative と同じ階層に絵文字を挟み込む
			if (decome || alternative){
				/*
				 * XXX
				 *  spモードメールのpopサーバに格納された時点で送信元が related で送っていても
				 *  multipart/related が multipart/mixed に変換されている模様。
				 *  iPhoneの MobileMail.app は multipart/mixed にある添付ファイルはインラインでも
				 *  添付ファイル扱いをしてしまうので本文と添付ファイルと二重に表示される。
				 *  iPhoneは逆に related に含まれる添付ファイルは通常の添付ファイルでも表示しないバグがあるため注意文挿入。
				 */
				if(conf.isForwardFixMultipartRelated() && (decome || emojiToCid.size()>0)){
					log.info(subtype+"->related 強制変更");
					newMimeMultipart.setSubType("related");
				}
				
				addEmojiPart(newMimeMultipart);
			}
			
			MimeBodyPart newPart = new MimeBodyPart();
			newPart.setContent(newMimeMultipart);
			if (newMimeMultipart.getCount()>0){
				parentPart.addBodyPart(newPart);
			}

		} else {
			String disposition = p.getDisposition();
			try{
				if(p instanceof MimeBodyPart){
					String contentId = ((MimeBodyPart)p).getContentID();
					if(contentId!=null){
						if(this.decomeFlg)isInlineImage = true;
						/*
						 * ドコモが <> をつけないContent-IDのメールを送ってくるので <> をつける。
						 * Content-IDはMessage-IDと同じルールが適用されるので <> なしはRFC違反。
						 */
						if(!contentId.matches("^<.*")){
							((MimeBodyPart)p).setContentID("<" + contentId + ">");
						}
					}
				}
				parentPart.addBodyPart((BodyPart)p);
			}catch(ClassCastException e){
				// メールがマルチパートではなく本文が添付ファイルだけの場合は、マルチパートにして添付ファイルをつける
				try {
					InternetHeaders newPartHeader = new InternetHeaders();
					newPartHeader.setHeader("Content-Type", p.getContentType());
					if(disposition != null){
						newPartHeader.setHeader("Content-Disposition", p.getDisposition());
					}
					newPartHeader.setHeader("Content-Transfer-Encoding", "base64");
					byte contentData[] = Util.inputstream2bytes(p.getInputStream());
					byte b64data[] = Base64.encodeBase64(contentData);
					MimeBodyPart newPart = new MimeBodyPart(newPartHeader, b64data);
					parentPart.addBodyPart((BodyPart)newPart);
				} catch (IOException ie) {
					log.error("未知のエラー",ie);
				}
			}
			// information
			log.info("添付ファイル追加");
			if (disposition != null && disposition.equals(Part.INLINE)) {
				// インライン添付ファイル追加処理
				log.info("Content-Type: "+p.getContentType());
				log.info("Content-Disposition: INLINE");
				if(this.decomeFlg)isInlineImage = true;
			} else if (disposition != null && disposition.equals(Part.ATTACHMENT)) {
				// 添付ファイル追加処理
				log.info("Content-Type: "+p.getContentType());
				log.info("Content-Disposition: ATTACHEMENT");
			} else {
				// その他（未考慮）
				log.info("Content-Type: "+p.getContentType());
			}
		}
		return isInlineImage;
	}

	private static String getSubtype(String contenttype){
		try{
			String r = contenttype.split("\\r?\\n")[0];
			return r.split("/")[1].replaceAll("\\s*;.*", "");
		}catch (Exception e) {
		}
		return "";
	}

	private void addEmojiPart(MimeMultipart parentPart) throws MessagingException{
		List<URL> urls = new ArrayList<URL>();
		for (Map.Entry<URL, String> e : emojiToCid.entrySet()){
			URL url = e.getKey();
			String cid = e.getValue();
			String name = emojiToCode.get(url);

			MimeBodyPart mbp = new MimeBodyPart();
			mbp.setDataHandler(new DataHandler(new URLDataSource(url)));
			mbp.setFileName(name);
			mbp.setDisposition("inline");
			mbp.setContentID("<" + cid + ">");
			
			log.info("絵文字追加:"+name);
			parentPart.addBodyPart(mbp);
			urls.add(url);
		}
		for (URL url : urls){
			emojiToCid.remove(url);
		}
	}

	/*
	 * 絵文字の置き換えは行わない
	 */
	private void setBodyDontReplace() throws EmailException{
		String html = this.htmlBody;
		String plain = this.plainBody;
		if(this.decomeFlg){
			// HTMLメール（このplainや以下のisEmptyのplainは使われないはず）
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
			//this.setHtmlMsg(html);
			this.setHtml(html);
			if(conf.isMailAlternative()||this.hasPlain){
				//this.setTextMsg(plainText);
				this.setText(plainText);
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
		
		//Map<URL, String> emojiToCid = new HashMap<URL, String>();
		emojiToCid = new HashMap<URL, String>();
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
						//cid = this.embed(emojiUrl, "emoji"+((int)c));
						cid = randomAlphabetic(MyHtmlEmail.CID_LENGTH).toLowerCase();
						emojiToCid.put(emojiUrl, cid);
						emojiToCode.put(emojiUrl, "emoji"+((int)c));
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

	// MimeMultipartの親子関係が保てないのでHtmlEmailのsetTextMsg,setHtmlMsgは使用しない 
	private void setText(String text){
		bodyMap.put(this.keyPlainBody, text);
	}
	private void setHtml(String html){
		html = Util.replaceUnicodeMapping(html);
		html += "\n";
		try{
			html = new String(html.getBytes(this.charset));
		}catch (Exception e) {
			log.error("setHtmlMsg",e);
		}
		if(decomeFlg){
			bodyMap.put(this.keyHtmlBody, html);
		}else{
			bodyMap.put("<html>"+this.keyPlainBody, html);
		}
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
				if (smmDate!=null){
					msg.setHeader("Date", df.format(this.smmDate));
				}

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
			if(isSent){
				subject = conf.getSentSubjectAppendPrefix()+smmSubject+conf.getSentSubjectAppendSuffix();
			}else{
				subject = conf.getSubjectAppendPrefix()+smmSubject+conf.getSubjectAppendSuffix();
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
			log.error("buildMimeMessage()でエラー発生",e);
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

	public static void setDocomoCharConv(CharacterConverter docomoCharConv) {
		SpmodeForwardMail.docomoCharConv = docomoCharConv;
	}

	public static void setStrConv(Map<Config, StringConverter> strConvMap) {
		SpmodeForwardMail.strConvMap = strConvMap;
	}
	
	/*
	 * Push通知に既存のAPIを使用するためImodeMail形式の取得
	 */
	public ImodeMail getImodeMail() {
		ImodeMail imodemail = new ImodeMail();
		imodemail.setFromAddr(this.smmFromAddr);
		imodemail.setDecomeFlg(false);
		if (this.smmDate!=null){
			SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmss");
			imodemail.setTime(df.format(this.smmDate));
		}
		String subject = this.subjectCharConv.convert(this.smmSubject);
		imodemail.setSubject(subject);
		String body = bodyMap.get(this.keyPlainBody);
		if (body!=null){
			imodemail.setBody(body);
		}else{
			imodemail.setBody("(本文取得失敗)");
		}
		return imodemail;
	}
}
