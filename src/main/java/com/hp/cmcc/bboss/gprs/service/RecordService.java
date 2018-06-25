package com.hp.cmcc.bboss.gprs.service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.hp.cmcc.bboss.gprs.pojo.BbdcTypeCdr;
import com.hp.cmcc.bboss.gprs.pojo.FieldObject;
import com.hp.cmcc.bboss.gprs.pojo.GprsRecFilePara;
import com.hp.cmcc.bboss.gprs.pojo.HandleReturnPara;


/**
 * @author Administrator
 *
 */
@Service
public class RecordService {
	
	@Autowired
	RestTemplate rt;
	
	@Autowired
	@Qualifier("primaryJdbcTemplate")
	protected JdbcTemplate jdbcTemplate1;
	
	Logger L = LoggerFactory.getLogger(RecordService.class);
	
	public String[] strToArr(String record){
		return record.split(",");
	}
		
	/**
	 * @param 规则
	 * @param 文件记录
	 * @param 记录hash
	 * @param 文件名
	 * @return 一条记录中处理后对应字段名，字段索引，字段值封装成的对象
	 */
	public List<FieldObject> createCdrData(List<BbdcTypeCdr> rule,String record,String fn){
		
		String[] S = strToArr(record);
//		String[] S = validation(record,cdr);//后期校验
		rule.sort((x,y) -> Integer.compare(x.getHinderIdx().intValue(), y.getHinderIdx().intValue()));
		List<FieldObject> list = new LinkedList<>();
		Map<String, BbdcTypeCdr> map = getRuleMap(rule);
		String key = getKeyWord(S,map); 
		for (Entry<String, BbdcTypeCdr> entry : map.entrySet()) { 
			BbdcTypeCdr cdr = entry.getValue(); 
			FieldObject fieldObject = new FieldObject();
			fieldObject.setFi(cdr.getHinderIdx().intValue());
			fieldObject.setFn(cdr.getFieldName());
			fieldObject.setSeptor(cdr.getDataSeparator());
			if(cdr.getFormerIdx() == -1L) {
				if("CREATE_DATE".equals(cdr.getFieldName().toUpperCase())){
					fieldObject.setfv(cdr.getDataFiller());
				}
				if("FILE_NAME".equals(cdr.getFieldName().toUpperCase())){
					fieldObject.setfv(fn);
				}
				if("BDC_CODE".equals(cdr.getFieldName().toUpperCase())){
					fieldObject.setfv(cdr.getDataFiller());
				}
				if("OPER_SERIAL_NBR".equals(cdr.getFieldName().toUpperCase())){
					String osn = null;
					try {
						osn = getOperSerialNbrByKey(key);
						fieldObject.setfv(osn);
					} catch (Exception e) {
						L.error("[the OPER_SERIAL_NBR for RECORD_HASH:"+key+" is null or not exist!]",e);
						fieldObject.setfv(osn);
						S[getErrCodeIndex(map)] = "F999";
					}
				}
			}else {
				fieldObject.setfv(S[cdr.getFormerIdx().intValue()]);
			}
			list.add(fieldObject);
		}
		return list;
	}
	
	public int getErrCodeIndex(Map<String, BbdcTypeCdr> map) {
		return map.get("ERR_CODE").getFormerIdx().intValue();
	}
	
	/**
	 * @param 关键字
	 * @return 操作流水
	 * @throws Exception
	 */
	private String getOperSerialNbrByKey(String key) throws Exception{
		String s = jdbcTemplate1.queryForObject("select OPER_SERIAL_NBR from import.bdc_gprs_011701_t "
					+ "where RECORD_HASH='"+key+"'" , String.class);
		return s;
	}

	/**
	 * @param s:记录转化后的数组
	 * @param map:规则
	 * @return 关键字
	 */
	private String getKeyWord(String[] s,Map<String,BbdcTypeCdr> map) {
		String key = "";
		for (Entry<String, BbdcTypeCdr> entry : map.entrySet()) {
			if("RECORD_HASH".equals(entry.getKey().toUpperCase())) {
				key = s[entry.getValue().getFormerIdx().intValue()];
			}
		}
		return key;
	}
	
	/**
	 * @param rule:规则
	 * @return 以FIELD_NAME为key，规则为value的map
	 */
	private Map<String,BbdcTypeCdr> getRuleMap(List<BbdcTypeCdr> rule) {
		Map<String,BbdcTypeCdr> map = new HashMap<String,BbdcTypeCdr>();
		for(BbdcTypeCdr cdr : rule) {
			map.put(cdr.getFieldName(), cdr);
		}
		return map;
	}

	/**
	 * @param 根据记录转化为对象的List
	 * @return 拼接后的字符串
	 */
	public String createOutRecord(List<FieldObject> D){
		StringBuffer sb = new StringBuffer();
		D.sort((x,y) -> Integer.compare(x.getFi(), y.getFi()));
		for(FieldObject d : D) {
			sb.append(setSqlFieldStr(d)+",");
		}
		return sb.toString().substring(0, sb.toString().length()-1);
	}

	/**
	 * @param 文件体
	 * @param 规则
	 * @return 错单数量
	 */
	public Integer getErrNum(List<String> fileBody,List<BbdcTypeCdr> rule) {
		Integer errNum = 0;
		for(BbdcTypeCdr cdr : rule) {
			if("ERR_CODE".equals(cdr.getFieldName().toUpperCase())) {
				for(String s : fileBody) {
					if(s.split(",")//处理后的记录转化的数组
							[cdr.getHinderIdx().intValue()].trim()//根据错码下标获取错码
									.startsWith("'F")) {//判断是否为错码
						errNum++;
					}
				}
				break;
			}
		}
		return errNum;
	}

	/**
	 * @param 被调用时获取到的参数对象
	 * @return 处理后的传递参数对象
	 */
	public HandleReturnPara HandleRecord(GprsRecFilePara grfp) {
		if(grfp == null) {
			L.error("[request data is null, pls check!]");
			return new HandleReturnPara();
		}
		List<String> fb = grfp.getFileBody();
		List<BbdcTypeCdr> rule = grfp.getRule();
		String fn = grfp.getFileName();
		
		List<String> fileBody = new LinkedList<>();;
		for(String re : fb) {
			String record = createOutRecord(createCdrData(rule, re ,fn));
			fileBody.add(record);
		}
		Integer errNum = getErrNum(fileBody,rule);
		HandleReturnPara hrp = new HandleReturnPara(fileBody, errNum);
		return hrp;
	}

	/**
	 * @param 一条记录
	 * @param 规则
	 * @return 重置错码后的记录
	 */
	@SuppressWarnings("unused")
	private String setErrCode(String re, List<BbdcTypeCdr> rule,String errCOde) {
		Map<String, BbdcTypeCdr> map = getRuleMap(rule);
		String[] record = strToArr(re);
		record[map.get("ERR_CODE").getFormerIdx().intValue()] = errCOde;
		return arrToRecord(record);
	}

	/**
	 * @param 记录转化后的数组
	 * @return 以“,”为分隔符的字符串
	 */
	private String arrToRecord(String[] record) {
		StringBuffer re = new StringBuffer("");
		for(int i = 0;i < record.length;i++) {
			re.append(record[i]+",");
		}
		return re.toString().substring(0, re.length()-1);
	}
	
	public String setSqlFieldStr(FieldObject fo) {
		if("DATE".equals(fo.getFt().toUpperCase())) {
			return fo.getfv();
		} 
		return "'"+fo.getfv()+"'";
	}
	
}
