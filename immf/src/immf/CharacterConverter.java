package immf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.internet.MimeUtility;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class CharacterConverter {
	private static final Log log = LogFactory.getLog(CharacterConverter.class);
	private Map<Integer,String> replaceMap;
	private Map<Integer,Map<Integer,String>> compositionReplaceMap;
	private boolean convertSoftbankSjis = false;
	
	public CharacterConverter(){
		this.replaceMap = new HashMap<Integer, String>();			
		this.compositionReplaceMap = new HashMap<Integer, Map<Integer,String>>();
	}
	
	public void load(File f) throws IOException{
		FileReader fr = null;
		BufferedReader br = null;
		try{
			// デフォルトエンコードで読み込まれる
			// wrapper.confで-Dfile.encoding=UTF-8を指定しているのでUTF-8になる
			fr = new FileReader(f);
			br = new BufferedReader(fr);
			String line=null;
			int lineCount=0;
			while((line=br.readLine())!=null){
				lineCount++;
				if(line.startsWith("#") || StringUtils.isBlank(line)){
					// [#]で始まる行はコメント
					continue;
				}
				// Unicode,Unicodeもしくは文字列
				String[] vals = line.split(",",2);
				if(vals.length!=2){
					continue;
				}
				try{
					String to = null;
					if(vals[1].matches("^[0-9a-fA-F]+$")){
						// 16進数の場合はUnicode
						int unicode = Integer.parseInt(vals[1], 16);
						to = String.valueOf(Character.toChars(unicode));
					}else if(vals[1].matches("^[0-9a-fA-F]+[+][0-9a-fA-F]+$")){
						String pair[] = vals[1].split("[+]");
						int unicode1 = Integer.parseInt(pair[0], 16);
						int unicode2 = Integer.parseInt(pair[1], 16);
						to = String.valueOf(Character.toChars(unicode1));
						to += String.valueOf(Character.toChars(unicode2));
					}else{
						to = vals[1];
					}

					if(vals[0].matches("^[0-9a-fA-F]+$")){
						int from = Integer.parseInt(vals[0], 16);
						log.debug("CharConv From ["+String.valueOf(Character.toChars(from))+"("+vals[0]+")], to ["+to+"("+vals[1]+")]");
						this.replaceMap.put(from, to);
					}else if(vals[0].matches("^[0-9a-fA-F]+[+][0-9a-fA-F]+$")){
						String pair[] = vals[0].split("[+]");
						int from1 = Integer.parseInt(pair[0], 16);
						int from2 = Integer.parseInt(pair[1], 16);
						Map<Integer,String> subCompositionReplaceMap = this.compositionReplaceMap.get(from2);
						if(subCompositionReplaceMap==null){
							subCompositionReplaceMap = new HashMap<Integer, String>();
							this.compositionReplaceMap.put(from2, subCompositionReplaceMap);
						}
						log.debug("CharConv From ["+from1+"+"+from2+"], to ["+to+"("+vals[1]+")]");
						subCompositionReplaceMap.put(from1, to);
					}

				}catch (Exception e) {
					log.warn("文字変換表 "+f.getName()+"("+lineCount+"行目)に問題があります。");
				}
			}
		}finally{
			Util.safeclose(br);
			Util.safeclose(fr);
		}
		
	}
	
	public String convert(String str){
		if(this.replaceMap.isEmpty()){
			return str;
		}
		StringBuilder buf = new StringBuilder();
		for (int i = 0; i < str.length(); ) {
			int cp = str.codePointAt(i);
			String s = this.replaceMap.get(cp);
			i += Character.charCount(cp);
			
			// 2文字までの結合文字への対応
			if(i<str.length()){
				int ncp = str.codePointAt(i);
				Map<Integer,String> subCompositionReplaceMap = this.compositionReplaceMap.get(ncp);
				if(subCompositionReplaceMap!=null){
					String s2 = subCompositionReplaceMap.get(cp);
					if(s2!=null){
						s=s2;
						i += Character.charCount(ncp);
					}
				}
			}
			if(s==null){
				// 置換なし
				buf.appendCodePoint(cp);
			}else{
				System.err.printf("U+%X=>%s", cp, s);
				buf.append(s);
			}
		}
		return buf.toString();
	}
	
	public String convert(String str, String charset) {
		if (this.convertSoftbankSjis && charset != null &&
			(charset.equalsIgnoreCase("Shift_JIS") || charset.equalsIgnoreCase("CP932")))
			str = SoftbankSjisConverter.convert(str);
		return convert(str);
	}

	private static final Pattern charsetPattern  = Pattern.compile("=\\?(\\w*)\\?[BQ]\\?[^\\s?]*\\?=");

	public String convertSubject(String subject) throws UnsupportedEncodingException {
		String charset = null;
		Matcher m = charsetPattern.matcher(subject);
		if (m.find())
			charset = m.group(1);
		return convert(MimeUtility.decodeText(subject), charset);
	}
	
	public void setConvertSoftbankSjis(boolean convertSoftbankSjis) {
		this.convertSoftbankSjis = convertSoftbankSjis;
	}
}
