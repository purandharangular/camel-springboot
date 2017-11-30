package my.company.route;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.camel.Body;
import org.apache.camel.ExchangeException;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.language.Simple;
import org.apache.camel.processor.aggregate.UseOriginalAggregationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import my.company.model.City;

@Component
public class GetCityInfoBuilder extends RouteBuilder {
	private static final Logger log = LoggerFactory.getLogger(GetCityInfoBuilder.class);
	
	public static String SP_GETZIPS = "GETZIPS(\n" + 
			"  CHAR ${header.cityName},\n" + 
			"  OUT INTEGER STATUS,\n" + 
			"  OUT CHAR MESSAGE\n" + 
			")";
	
	@Value("${cityInfoDatabase.schema:#{null}}")
	String schema;

	@Override
	public void configure() throws Exception {
		//OnExcpetion handlers apply only on the routes defined in this RouteBuilder
		onException(Exception.class)
			.handled(true)
			.log(LoggingLevel.ERROR, "Error getting city info. ${exception.message}")
			.to("log:getCityInfo.error?showAll=true&multiline=true&level=ERROR")
			.bean(GetCityInfoBuilder.class, "addErrorToCity");
		
		//An City object is expected as exchangeProperty.city having the name already.
		//This object will be enriched by the route. This is easier than writing an AggregatorStrategy
		from("direct:getCityInfo").routeId("getCityInfo")
			//prepare and call stored proceedure
			.removeHeaders("*", MyBuilder.HEADER_BUSINESSID)
			.setHeader("cityName", simple("${exchangeProperty.city?.name}"))
			.setBody((constant(schema == null ? SP_GETZIPS : schema+"."+SP_GETZIPS)))
			.to("sql-stored:GetZips?useMessageBodyForTemplate=true&dataSource=#cityInfoDS")
			
			//Process stored proc response
			.choice()
				.when(simple("${body[STATUS]} == '0'"))
					.setBody(simple("${body[#result-set-1]}")) //A stored procedure may also return multiple resultsets
					.bean(GetCityInfoBuilder.class, "processResultset")
				.otherwise()
					.log(LoggingLevel.WARN, "Failed to get zips for ${header.cityName}")
					.bean(GetCityInfoBuilder.class, "throwStatusError")
			;
		
	}
	
	//Route helper methods
	
	//Get values from ZIPCODE column and store in City object
	public static void processResultset(
			@Body List<Map<String,Object>> resultset, 
			@ExchangeProperty("city") City city) {
		List<String> zips = resultset.stream()
				.map(m->(String)m.get("ZIPCODE"))
				.collect(Collectors.toList());
		city.setZips(zips);
	}
	
	public static void throwStatusError(
			@Simple("${body[STATUS]}") String status,
			@Simple("${body[MESSAGE]}") String message
			) throws Exception {
		throw new Exception(MessageFormat.format("Status {0} - {1}", status, message));
	}
	
	public static void addErrorToCity(@ExchangeProperty("city") City city, @ExchangeException Exception ex) {
		if (city != null)
			city.setError(ex.getMessage());
	}
}
