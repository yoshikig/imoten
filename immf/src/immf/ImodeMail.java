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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.mail.ByteArrayDataSource;

public class ImodeMail {
	private static final Log log = LogFactory.getLog(ImodeMail.class);
	public static final int RECV_TYPE_TO = 1;
	public static final int RECV_TYPE_CC = 2;
	public static final int RECV_TYPE_BCC = 3;


	private String mailId;
	private int folderId;
	private String subject;
	private String time;
	private boolean decomeFlg;
	private int recvType;
	private String body;
	private String myAddr;
	private InternetAddress fromAddr;
	private List<InternetAddress> toAddrList = new ArrayList<InternetAddress>();
	private List<InternetAddress> ccAddrList = new ArrayList<InternetAddress>();;

	private List<AttachedFile> attachFileList = new ArrayList<AttachedFile>();
	private List<AttachedFile> inlineFileList = new ArrayList<AttachedFile>();

	private List<String> otherInfoList = new ArrayList<String>();

	private List<String> groupList = new ArrayList<String>();

	public String toLoggingString(){
		StrBuilder buf = new StrBuilder();
		buf.appendln("FolderID     "+this.folderId);
		buf.appendln("MailID       "+this.mailId);
		buf.appendln("Subject      "+this.subject);
		buf.appendln("Time         "+this.mailId);
		buf.appendln("Decome       "+this.decomeFlg);
		buf.appendln("RecvType     "+this.recvType);
		buf.appendln("MyAddr       "+this.myAddr);
		buf.appendln("From         "+this.fromAddr.toUnicodeString());
		for (InternetAddress to : this.toAddrList) {
			buf.appendln("To           "+to.toUnicodeString());
		}
		for (InternetAddress cc : this.ccAddrList) {
			buf.appendln("Cc           "+cc.toUnicodeString());
		}
		for(AttachedFile f : this.attachFileList){
			buf.appendln("AttachFile ---- "+f.getFilename());
			buf.appendln("  ID            "+f.getId());
			buf.appendln("  ContentType   "+f.getContentType());
			buf.appendln("  Size          "+f.getData().length);
		}
		for(AttachedFile f : this.attachFileList){
			buf.appendln("InlineFile ---- "+f.getFilename());
			buf.appendln("  ID            "+f.getId());
			buf.appendln("  ContentType   "+f.getContentType());
			buf.appendln("  Size          "+f.getData().length);
		}
		buf.appendln("Body -----");
		buf.appendln(this.body);
		buf.appendln("----------");
		return buf.toString();
	}

	public String getMailId() {
		return mailId;
	}

	public void setMailId(String mailId) {
		this.mailId = mailId;
	}

	public int getFolderId() {
		return folderId;
	}

	public void setFolderId(int folderId) {
		this.folderId = folderId;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public Date getTimeDate(){
		SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmss");
		try{
			return df.parse(this.time);
		}catch (Exception e) {
			return null;
		}
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public boolean isDecomeFlg() {
		return decomeFlg;
	}

	public void setDecomeFlg(boolean decomeFlg) {
		this.decomeFlg = decomeFlg;
	}

	public int getRecvType() {
		return recvType;
	}

	public void setRecvType(int recvType) {
		this.recvType = recvType;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}

	public InternetAddress getFromAddr() {
		return fromAddr;
	}

	public void setFromAddr(InternetAddress fromAddr) {
		this.fromAddr = fromAddr;
	}

	public List<AttachedFile> getAttachFileList() {
		return attachFileList;
	}

	public void setAttachFileList(List<AttachedFile> attachFileList) {
		this.attachFileList = attachFileList;
	}

	public List<InternetAddress> getToAddrList() {
		return toAddrList;
	}

	public void setToAddrList(List<InternetAddress> toAddrList) {
		this.toAddrList = toAddrList;
	}

	public List<InternetAddress> getCcAddrList() {
		return ccAddrList;
	}

	public void setCcAddrList(List<InternetAddress> ccAddrList) {
		this.ccAddrList = ccAddrList;
	}

	public List<AttachedFile> getInlineFileList() {
		return inlineFileList;
	}

	public void setInlineFileList(List<AttachedFile> inlineFileList) {
		this.inlineFileList = inlineFileList;
	}

	public String getMyAddr() {
		return myAddr;
	}

	public void setMyAddr(String myAddr) {
		this.myAddr = myAddr;
	}
	public String getMyMailAddr(){
		return this.myAddr+"@docomo.ne.jp";
	}
	public InternetAddress getMyInternetAddress(){
		try{
			return new InternetAddress(this.getMyMailAddr());
		}catch (Exception e) {
			log.error("getMyInternetAddress Error."+this.getMyMailAddr(),e);
			return null;
		}
	}
	public void addOtherInfo(String s){
		this.otherInfoList.add(s);
	}
	public List<String> getOtherInfoList(){
		return this.otherInfoList;
	}

	public void addGroupList(String groupName) {
		this.groupList.add(groupName);
	}

	public List<String> getGroupList() {
		return groupList;
	}

	public Message getMessage() {
		Session session = Session.getInstance(new Properties());
		MimeMessage m = new MimeMessage(session);
		String text = body;
		
		
		if (decomeFlg) {
			for (AttachedFile f : inlineFileList) {
				text = StringUtils.replace(text, f.getId(), "cid:"+f.getId());
			}
		}
		
		try{
			// ヘッダ
			m.setFrom(fromAddr);
			switch(recvType){
			case RECV_TYPE_TO:
				m.addRecipients(Message.RecipientType.TO, getMyMailAddr());
				break;
			case RECV_TYPE_CC:
				m.addRecipients(Message.RecipientType.CC, getMyMailAddr());
				break;
			case RECV_TYPE_BCC:
				break;
			}
			for (InternetAddress addr : toAddrList){
				m.addRecipients(Message.RecipientType.TO, addr.getAddress());
			}
			for (InternetAddress addr : ccAddrList){
				m.addRecipients(Message.RecipientType.CC, addr.getAddress());
			}
			m.setSubject(MimeUtility.encodeText(subject, "Shift_JIS", "B"));
			m.setSentDate(getTimeDate());

			// ボディ
			if (attachFileList.size()==0 && inlineFileList.size()==0){
				if (decomeFlg){
					m.setText(text, "Shift_JIS", "html");
					m.setHeader("Content-Type", "text/html; charset=Shift_JIS");
				}else{
					m.setText(text, "Shift_JIS");
					m.setHeader("Content-Type", "text/plain; charset=Shift_JIS");
				}
				m.setHeader("Content-Transfer-Encoding", "8bit");
				
			} else {
				MimeMultipart rootPart = new MimeMultipart("mixed");
				m.setContent(rootPart);
				m.addHeader("Content-Type", rootPart.getContentType());
				
				MimeBodyPart textPart = new MimeBodyPart();
				if (decomeFlg){
					textPart.setText(text, "Shift_JIS", "html");
					textPart.setHeader("Content-Type", "text/html; charset=Shift_JIS");
				}else{
					textPart.setText(text, "Shift_JIS");
					textPart.setHeader("Content-Type", "text/plain; charset=Shift_JIS");
				}
				textPart.setHeader("Content-Transfer-Encoding", "base64");

				if (inlineFileList.size()>0){
					MimeMultipart relatedMultipart = new MimeMultipart("related");
					MimeBodyPart relatedBodyPart = new MimeBodyPart();
					relatedBodyPart.setContent(relatedMultipart);
					relatedBodyPart.addHeader("Content-Type", relatedMultipart.getContentType());
					rootPart.addBodyPart(relatedBodyPart);

					relatedMultipart.addBodyPart(textPart);

					for (AttachedFile f : inlineFileList) {
						
						InternetHeaders thisPartHeader = new InternetHeaders();
						thisPartHeader.setHeader("Content-Type", f.getContentType());
						thisPartHeader.setHeader("Content-Transfer-Encoding", "base64");
						MimeBodyPart thisPart = new MimeBodyPart(thisPartHeader, f.getBase64Data());
						
						Util.setFileName(thisPart, f.getFilename(), "Shift_JIS", null);
						thisPart.setDisposition(BodyPart.INLINE);
						thisPart.setContentID("<"+f.getId()+">");
						relatedMultipart.addBodyPart(thisPart);
					}
				} else {
					rootPart.addBodyPart(textPart);
				}
				
				if (attachFileList.size()>0) {
					for (AttachedFile f : attachFileList) {
						InternetHeaders thisPartHeader = new InternetHeaders();
						thisPartHeader.setHeader("Content-Type", f.getContentType());
						thisPartHeader.setHeader("Content-Transfer-Encoding", "base64");
						MimeBodyPart thisPart = new MimeBodyPart(thisPartHeader, f.getBase64Data());

						Util.setFileName(thisPart, f.getFilename(), "Shift_JIS", null);
						thisPart.setDisposition(BodyPart.ATTACHMENT);
						rootPart.addBodyPart(thisPart);
					}
				}
			}
			
		}catch (Exception e){
			log.error("Message作成中のエラー",e);
			m = null;
		}
		
		//StringBuilder maildata = Util.dumpMessage(m);
		//log.info("作成メール情報\n"+maildata);

		return m;
	}
}
