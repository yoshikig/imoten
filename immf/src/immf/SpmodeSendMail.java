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
import java.util.ArrayList;
import java.util.List;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;

public class SpmodeSendMail extends MyHtmlEmail {
	private static final Log log = LogFactory.getLog(SpmodeForwardMail.class);
	private MimeMessage smm;
	private String plainBody = "";
	private String htmlBody = "";
	private List<String> smmRecipientsList = new ArrayList<String>();
	private Config conf;
	private static CharacterConverter charConv = null;
	private static CharacterConverter goomojiSubjectCharConv = null;

	public SpmodeSendMail(MyWiserMessage sm, Config conf) throws EmailException{
		this.conf = conf;
		try {
			this.smm = sm.getMimeMessage();
		} catch (MessagingException e1) {
			log.warn("error");
		}

		this.setDebug(conf.isMailDebugEnable());
		this.setCharset("Shift_JIS");

		// SMTP Server
		this.setHostName("mail.spmode.ne.jp");
		this.setSmtpPort(465);
		this.setSocketConnectionTimeout(conf.getSmtpConnectTimeoutSec()*1000);
		this.setSocketTimeout(conf.getSmtpTimeoutSec()*1000);
		this.setSSL(true);
		
		String myname = conf.getSpmodeMailUser();
		// あえてimoten.ini設定値を使わないでハードコード
		String mymailaddr = myname + "@docomo.ne.jp";
		String passwd = conf.getSpmodeMailPasswd();

		if(myname!=null&&passwd!=null){
			this.setAuthentication(myname, passwd);
		}

		try{
			// XXX ImodeNetClient.java getMail() アドレス帳との連携は未
			// From:
			InternetAddress smmFromAddr = (InternetAddress) smm.getFrom()[0];
			if (!smmFromAddr.getAddress().equalsIgnoreCase(mymailaddr)){
				log.warn("送信元アドレスがspモードのアドレスではないためspモードのアドレスに修正します。");
				smmFromAddr.setAddress(mymailaddr);
			}
			this.setFrom(smmFromAddr.getAddress(), encodeSjisText(smmFromAddr.getPersonal()));

			Address[] ar;
			List<String> smmToCcAddrList = new ArrayList<String>();
			// To:
			ar = smm.getRecipients(Message.RecipientType.TO);
			if (ar != null){
				for (Address addr : ar){
					InternetAddress ia = (InternetAddress) addr;
					// XXX 無指定だとwindows-31jで、以下の指定をするとiso-2022-jpでエンコードされる。
					// 混在は文字化けの原因になりそうだが・・・
					this.addTo(ia.getAddress(), encodeSjisText(ia.getPersonal()));
					smmToCcAddrList.add(ia.getAddress());
					log.info("To:"+ia.getAddress());
				}
			}
			// Cc:
			ar = smm.getRecipients(Message.RecipientType.CC);
			if (ar != null){
				for (Address addr : ar){
					InternetAddress ia = (InternetAddress) addr;
					this.addCc(ia.getAddress(), encodeSjisText(ia.getPersonal()));
					smmToCcAddrList.add(ia.getAddress());
					log.info("Cc:"+ia.getAddress());
				}
			}
			// Bcc:
			addSmmRecipientsList(sm.getEnvelopeReceiver());
			List<String> smmBccAddrList = new ArrayList<String>();
			smmBccAddrList = smmRecipientsList;
			smmBccAddrList.removeAll(smmToCcAddrList);
			for (String addr : smmBccAddrList) {
				this.addBcc(addr);
				log.info("Bcc:"+addr);
			}
			// Reply-to:
			for (Address addr : smm.getReplyTo()){
				InternetAddress ia = (InternetAddress) addr;
				this.addReplyTo(ia.getAddress(), encodeSjisText(ia.getPersonal()));
				log.info("Reply-to:"+ia.getAddress());
			}

			// メールを分解
			parsePart(smm);
			
			if(this.plainBody.isEmpty()&&this.htmlBody.isEmpty())
				this.setTextMsg(" ");
			
		} catch (MessagingException e) {
			
		}
	}

	private void parsePart(Part p) throws MessagingException {
		if (this.plainBody.isEmpty() && p.isMimeType("text/plain")) {
			try {
				this.plainBody = p.getContent().toString();
				log.info("plainBody["+plainBody+"]");
				plainBody = SpmodeSendMail.charConv.convert(plainBody, "UTF-8");
				this.setTextMsg(plainBody);
			} catch (Exception e) {
				log.warn("parse plainBody error",e);
				this.plainBody = "";
			}
		} else if (this.htmlBody.isEmpty() && p.isMimeType("text/html")) {
			try {
				this.htmlBody = p.getContent().toString();
				log.info("htmlBody["+htmlBody+"]");
				htmlBody = SpmodeSendMail.charConv.convert(htmlBody, "UTF-8");
				this.setHtmlMsg(htmlBody);
			} catch (Exception e) {
				log.warn("parse htmlBody error",e);
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

	@Override
	public void buildMimeMessage() throws EmailException {
		super.buildMimeMessage();
		MimeMessage msg = this.getMimeMessage();
		try{
			msg.setHeader("X-Mailer", ServerMain.Version);

			String smmSubject = smm.getSubject();
			log.info("Subject:"+smmSubject);
			try {
				if (smmSubject != null) {
					smmSubject = SpmodeSendMail.charConv.convertSubject(smmSubject);
				}
				if (conf.isSenderUseGoomojiSubject()) {
					String goomojiSubject = smm.getHeader("X-Goomoji-Subject", null);
					if (goomojiSubject != null)
						smmSubject = SpmodeSendMail.goomojiSubjectCharConv.convertSubject(goomojiSubject);
				}
			}catch(Exception e){
				log.warn("Subjectの絵文字変換に失敗",e);
				smmSubject = smm.getSubject();
			}

			msg.setSubject(encodeSjisText(smmSubject));

			//if(this.conf.getContentTransferEncoding()!=null){
			//	msg.setHeader("Content-Transfer-Encoding", this.conf.getContentTransferEncoding());
			//}

		}catch (Exception e) {
			log.warn(e);
		}
	}

	@Override
	public MyHtmlEmail setTextMsg(String plain) throws EmailException {
		plain = Util.reverseReplaceUnicodeMapping(plain);
		try{
			plain = SjisString(plain);
		}catch (Exception e) {
			log.error("setTextMsg",e);
		}
		return super.setTextMsg(plain);
	}

	@Override
	public MyHtmlEmail setHtmlMsg(String html) throws EmailException {
		html = Util.reverseReplaceUnicodeMapping(html);
		try{
			html = SjisString(html);
		}catch (Exception e) {
			log.error("setHtmlMsg",e);
		}
		return super.setHtmlMsg(html);
	}

	@Override
	public Email setSubject(String subject) {
		return super.setSubject(Util.reverseReplaceUnicodeMapping(subject));
	}

	private void addSmmRecipientsList(List<String> addrsList) {
		for (String addr : addrsList) {
			try{
				smmRecipientsList.add(addr);
			}catch (Exception e) {
				log.warn("addRecipients error "+addr,e);
			}
		}
	}
	
	private String SjisString(String text){
		try {
			if(text!=null){
				return new String(text.getBytes("Shift_JIS"),"Shift_JIS");
			}else{
				return null;
			}
		}catch(Exception e){
			log.warn("SJIS変換失敗:"+text,e);
			return text;
		}
	}
	
	private String encodeSjisText(String text){
		try {
			if(text!=null){
				return MimeUtility.encodeText(SjisString(text),"Shift_JIS","B");
			}else{
				return null;
			}
		}catch(Exception e){
			log.warn("SJISエンコード失敗:"+text,e);
			return text;
		}
	}

	public static void setCharConv(CharacterConverter charConv) {
		SpmodeSendMail.charConv = charConv;
	}

	public static void setGoomojiSubjectCharConv(CharacterConverter goomojiSubjectCharConv) {
		SpmodeSendMail.goomojiSubjectCharConv = goomojiSubjectCharConv;
	}
}
