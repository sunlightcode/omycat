package demo.catlets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import org.opencloudb.cache.LayerCachePool;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.config.model.SchemaConfig;
import org.opencloudb.config.model.SystemConfig;
import org.opencloudb.net.mysql.FieldPacket;
import org.opencloudb.net.mysql.RowDataPacket;
import org.opencloudb.route.RouteResultset;
import org.opencloudb.route.RouteResultsetNode;
import org.opencloudb.route.factory.RouteStrategyFactory;
import org.opencloudb.server.ServerConnection;
import org.opencloudb.server.parser.ServerParse;
import org.opencloudb.sharejoin.JoinParser;
import org.opencloudb.sqlengine.AllJobFinishedListener;
import org.opencloudb.sqlengine.Catlet;
import org.opencloudb.sqlengine.EngineCtx;
import org.opencloudb.sqlengine.SQLJobHandler;
import org.opencloudb.util.ByteUtil;
import org.opencloudb.util.ResultSetUtil;


//import org.opencloudb.route.RouteStrategy;
//import org.opencloudb.route.impl.DruidMysqlRouteStrategy;
//import org.opencloudb.parser.druid.DruidParser;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
/**  
 * 功能详细描述:分片join
 * @author sohudo[http://blog.csdn.net/wind520]
 * @create 2015年01月22日 下午6:50:23 
 * @version 0.0.1
 */

public class ShareJoin implements Catlet {
	private EngineCtx ctx;
	private RouteResultset rrs ;
	private JoinParser joinParser;
	
	private Map<String, byte[]> rows = new ConcurrentHashMap<String, byte[]>();
	private Map<String,String> ids = new ConcurrentHashMap<String,String>();
	//private ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<String>();
	
	private List<byte[]> fields; //主表的字段
	private ArrayList<byte[]> allfields;//所有的字段
	private boolean isMfield=false;
	private int mjob=0;
	private int maxjob=0;
	private int joinindex=0;//关联join表字段的位置
	private int sendField=0;
	private boolean childRoute=false;
	//重新路由使用
	private SystemConfig sysConfig; 
	private SchemaConfig schema;
	private int sqltype; 
	private String charset; 
	private ServerConnection sc;	
	private LayerCachePool cachePool;
	public void setRoute(RouteResultset rrs){
		this.rrs =rrs;
	}	
	
	public void route(SystemConfig sysConfig, SchemaConfig schema,int sqlType, String realSQL, String charset, ServerConnection sc,	LayerCachePool cachePool) {
		int rs = ServerParse.parse(realSQL);
		this.sqltype = rs & 0xff;
		this.sysConfig=sysConfig; 
		this.schema=schema;
		this.charset=charset; 
		this.sc=sc;	
		this.cachePool=cachePool;		
		try {
		 //  RouteStrategy routes=RouteStrategyFactory.getRouteStrategy();	
		  // rrs =RouteStrategyFactory.getRouteStrategy().route(sysConfig, schema, sqlType2, realSQL,charset, sc, cachePool);		   
			MySqlStatementParser parser = new MySqlStatementParser(realSQL);			
			SQLStatement statement = parser.parseStatement();
			if(statement instanceof SQLSelectStatement) {
			   SQLSelectStatement st=(SQLSelectStatement)statement;
			   SQLSelectQuery sqlSelectQuery =st.getSelect().getQuery();
				if(sqlSelectQuery instanceof MySqlSelectQueryBlock) {
					MySqlSelectQueryBlock mysqlSelectQuery = (MySqlSelectQueryBlock)st.getSelect().getQuery();
					joinParser=new JoinParser(mysqlSelectQuery,realSQL);
					joinParser.parser();
				}	
			}
		   /*	
		   if (routes instanceof DruidMysqlRouteStrategy) {
			   SQLSelectStatement st=((DruidMysqlRouteStrategy) routes).getSQLStatement();
			   SQLSelectQuery sqlSelectQuery =st.getSelect().getQuery();
				if(sqlSelectQuery instanceof MySqlSelectQueryBlock) {
					MySqlSelectQueryBlock mysqlSelectQuery = (MySqlSelectQueryBlock)st.getSelect().getQuery();
					joinParser=new JoinParser(mysqlSelectQuery,realSQL);
					joinParser.parser();
				}
		   }
		   */
		} catch (Exception e) {
		
		}
	}
	private void getRoute(String sql){
		try {
		  if (joinParser!=null){
			rrs =RouteStrategyFactory.getRouteStrategy().route(sysConfig, schema, sqltype,sql,charset, sc, cachePool);
		  }
		} catch (Exception e) {
			
		}
	}
	private String[] getDataNodes(){		
		String[] dataNodes =new String[rrs.getNodes().length] ;
		for (int i=0;i<rrs.getNodes().length;i++){
			dataNodes[i]=rrs.getNodes()[i].getName();
		}
		return dataNodes;
	}
	private String getDataNode(String[] dataNodes){
		String dataNode="";
		for (int i=0;i<dataNodes.length;i++){
			dataNode+=dataNodes[i]+",";
		}
		return dataNode;
	}
	public void processSQL(String sql, EngineCtx ctx) {
		String ssql=joinParser.getSql();
		getRoute(ssql);
		RouteResultsetNode[] nodes = rrs.getNodes();
		if (nodes == null || nodes.length == 0 || nodes[0].getName() == null
				|| nodes[0].getName().equals("")) {
			ctx.getSession().getSource().writeErrMessage(ErrorCode.ER_NO_DB_ERROR,
					"No dataNode found ,please check tables defined in schema:"
							+ ctx.getSession().getSource().getSchema());
			return;
		} 
		this.ctx=ctx;
		String[] dataNodes =getDataNodes();
		maxjob=dataNodes.length;
		ShareDBJoinHandler joinHandler = new ShareDBJoinHandler(this,joinParser.getJoinLkey());		
		ctx.executeNativeSQLSequnceJob(dataNodes, ssql, joinHandler);
    	EngineCtx.LOGGER.info("Catlet exec:"+getDataNode(getDataNodes())+" sql:" +ssql);

		ctx.setAllJobFinishedListener(new AllJobFinishedListener() {
			@Override
			public void onAllJobFinished(EngineCtx ctx) {
				ctx.writeEof();
				EngineCtx.LOGGER.info("发送数据OK"); 
			}
		});
	}
	
    public void putDBRow(String id,String nid, byte[] rowData,int findex){
    	rows.put(id, rowData);	
    	ids.put(id, nid);
    	joinindex=findex;
		//ids.offer(nid);
		int batchSize = 999;
		// 满1000条，发送一个查询请求
		if (ids.size() > batchSize) {
			createQryJob(batchSize);
		}            	
    }
    
    public void putDBFields(List<byte[]> mFields){
    	 if (!isMfield){
    		 fields=mFields; 
    	 }    	
    }    

   public void endJobInput(String dataNode, boolean failed){
	   mjob++;
	   if (mjob>=maxjob){
		 createQryJob(Integer.MAX_VALUE);
	     ctx.endJobInput();
	   }
	  // EngineCtx.LOGGER.info("完成"+mjob+":" + dataNode+" failed:"+failed);
   }
   
	//private void createQryJob(String dataNode,int batchSize) {	
	private void createQryJob(int batchSize) {	
		int count = 0;
		Map<String, byte[]> batchRows = new ConcurrentHashMap<String, byte[]>();
		String theId = null;
		StringBuilder sb = new StringBuilder().append('(');
		String svalue="";
		for(Map.Entry<String,String> e: ids.entrySet() ){
			theId=e.getKey();
			batchRows.put(theId, rows.remove(theId));
			if (!svalue.equals(e.getValue())){
			  sb.append(e.getValue()).append(',');
			}
			svalue=e.getValue();
			if (count++ > batchSize) {
				break;
			}			
		}
		/*
		while ((theId = ids.poll()) != null) {
			batchRows.put(theId, rows.remove(theId));
			sb.append(theId).append(',');
			if (count++ > batchSize) {
				break;
			}
		}
		*/
		if (count == 0) {
			return;
		}
		sb.deleteCharAt(sb.length() - 1).append(')');
		String sql = String.format(joinParser.getChildSQL(), sb);
		//if (!childRoute){
		  getRoute(sql);
		 //childRoute=true;
		//}
		ctx.executeNativeSQLParallJob(getDataNodes(),sql, new ShareRowOutPutDataHandler(this,fields,joinindex, batchRows));
		EngineCtx.LOGGER.info("SQLParallJob:"+getDataNode(getDataNodes())+" sql:" + sql);		
	}  
	public void writeHeader(String dataNode,List<byte[]> afields, List<byte[]> bfields) {
		sendField++;
		if (sendField==1){		  		  
		  ctx.writeHeader(afields, bfields);
		  setAllFields(afields, bfields);
		 // EngineCtx.LOGGER.info("发送字段2:" + dataNode);
		}
		
	}
	private void setAllFields(List<byte[]> afields, List<byte[]> bfields){		
		allfields=new ArrayList<byte[]>();
		for (byte[] field : afields) {
			allfields.add(field);
		}
		//EngineCtx.LOGGER.info("所有字段2:" +allfields.size());
		for (int i=1;i<bfields.size();i++){
			allfields.add(bfields.get(i));
		}
		
	}
	public List<byte[]> getAllFields(){		
		return allfields;
	}
	public void writeRow(RowDataPacket rowDataPkg){
		ctx.writeRow(rowDataPkg);
	}
}

class ShareDBJoinHandler implements SQLJobHandler {
	private List<byte[]> fields;
	private final ShareJoin ctx;
	private String joinkey;

	public ShareDBJoinHandler(ShareJoin ctx,String joinField) {
		super();
		this.ctx = ctx;
		this.joinkey=joinField;
		//EngineCtx.LOGGER.info("二次查询:"  +" sql:" + querySQL+"/"+joinkey);
	}

	//private Map<String, byte[]> rows = new ConcurrentHashMap<String, byte[]>();
	//private ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<String>();

	@Override
	public void onHeader(String dataNode, byte[] header, List<byte[]> fields) {
		this.fields = fields;
		ctx.putDBFields(fields);
	}
	
	public static int getFieldIndex(List<byte[]> fields,String fkey){
		int i=0;
		for (byte[] field :fields) {	
			  FieldPacket fieldPacket = new FieldPacket();
			  fieldPacket.read(field);	
			  if (ByteUtil.getString(fieldPacket.name).equals(fkey)){
				  return i;				  
			  }
			  i++;
			}
		return i;		
	}
	/*
	public static String getFieldNames(List<byte[]> fields){
		String str="";
		for (byte[] field :fields) {	
		  FieldPacket fieldPacket = new FieldPacket();
		  fieldPacket.read(field);	
		  str+=ByteUtil.getString(fieldPacket.name)+",";
		}
		return str;
	}
	
	public static String getFieldName(byte[] field){
		FieldPacket fieldPacket = new FieldPacket();
		fieldPacket.read(field);	
		return ByteUtil.getString(fieldPacket.name);
	}
	*/
	@Override
	public boolean onRowData(String dataNode, byte[] rowData) {
		int fid=getFieldIndex(fields,joinkey);
		String id = ResultSetUtil.getColumnValAsString(rowData, fields, 0);//主键，默认id
		String nid = ResultSetUtil.getColumnValAsString(rowData, fields, fid);
		// 放入结果集
		//rows.put(id, rowData);
		ctx.putDBRow(id,nid, rowData,fid);
		return false;
	}

	@Override
	public void finished(String dataNode, boolean failed) {
		ctx.endJobInput(dataNode,failed);
	}

}

class ShareRowOutPutDataHandler implements SQLJobHandler {
	private final List<byte[]> afields;
	private List<byte[]> bfields;
	private final ShareJoin ctx;
	private final Map<String, byte[]> arows;
	private int joini;
	public ShareRowOutPutDataHandler(ShareJoin ctx,List<byte[]> afields,int joini,Map<String, byte[]> arows) {
		super();
		this.afields = afields;
		this.ctx = ctx;
		this.arows = arows;		
		this.joini =joini;
		//EngineCtx.LOGGER.info("二次查询:" +arows.size()+ " afields："+FenDBJoinHandler.getFieldNames(afields));
    }

	@Override
	public void onHeader(String dataNode, byte[] header, List<byte[]> bfields) {
		  this.bfields=bfields;		
		  ctx.writeHeader(dataNode,afields, bfields);
	}
	
	//不是主键，获取join左边的的记录
	private byte[] getRow(String value,int index){
		for(Map.Entry<String,byte[]> e: arows.entrySet() ){
	        String key=e.getKey();
	        RowDataPacket rowDataPkg = ResultSetUtil.parseRowData(e.getValue(), afields);
	        String id = ByteUtil.getString(rowDataPkg.fieldValues.get(index));
	        if (id.equals(value)){
	        	return arows.remove(key);	
	        }
	     }
		return null;		
	}
	@Override
	public boolean onRowData(String dataNode, byte[] rowData) {
		RowDataPacket rowDataPkgold = ResultSetUtil.parseRowData(rowData, bfields);
		// 获取Id字段，
		String id = ByteUtil.getString(rowDataPkgold.fieldValues.get(0));		
		// 查找ID对应的A表的记录
		byte[] arow = getRow(id,joini);//arows.remove(id);
		while (arow!=null) {
		  RowDataPacket rowDataPkg = ResultSetUtil.parseRowData(arow,afields );//ctx.getAllFields());
		  for (int i=1;i<rowDataPkgold.fieldCount;i++){
		// 设置b.name 字段
		    byte[] bname = rowDataPkgold.fieldValues.get(i);	
		    rowDataPkg.add(bname);
		    rowDataPkg.addFieldCount(1);
		  }
		//RowData(rowDataPkg);
		  ctx.writeRow(rowDataPkg);
		   arow = getRow(id,joini);
		}
		return false;
	}
	

	@Override
	public void finished(String dataNode, boolean failed) {
	//	EngineCtx.LOGGER.info("完成2:" + dataNode+" failed:"+failed);
	}
}
