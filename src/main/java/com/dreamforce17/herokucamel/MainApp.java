package com.dreamforce17.herokucamel;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.salesforce.SalesforceComponent;
import org.apache.camel.component.salesforce.api.dto.CreateSObjectResult;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.salesforce.dto.Case;
import org.apache.camel.salesforce.dto.Case_PriorityEnum;
import org.apache.camel.salesforce.dto.Contact;
import org.apache.camel.salesforce.dto.QueryRecordsContact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MainApp {
	public static void main(String args[]) throws Exception {
		
		CamelContext context = new DefaultCamelContext();
		
		SalesforceComponent salesforce = new SalesforceComponent();
		salesforce.setLoginUrl("https://login.salesforce.com");
		salesforce.setClientSecret("7E9B1CB83324F8B6F72DF051DC0B7FE5BD689188182D261FB50B1429BC326BC4");
		salesforce.setClientId("3MVG9uudbyLbNPZNnIi61lPLByYblMWGD7.hcBQ.P7fexnQGroc2ZKRuU4dDKoIi2RpaoNHlJwwQLcyVtZ_wM");
		salesforce.setPassword("fwYH7utx6fwzGnLSRZz6NPKSMGQOKmx7LOYF");
		salesforce.setUserName("leonardopmacedo@outlook.com");
		salesforce.setPackages("org.apache.camel.salesforce.dto");
		
		context.addComponent("salesforce", salesforce);
		
		context.addRoutes(new RouteBuilder() {
			public void configure() {
				
		
				/*
				 * Route 1: convert incoming Telegram chat message to Salesforce case
				 * */
				
				/*
				 * Take incoming message from telegram
				 * */
				from("telegram:bots/823983250:AAEcFgnNwMFfR0ENcVEU46sK11xHpEkzptM")
					
					/*
					 * Save text of the message to message header
					 * */
					.process(new Processor() {
						@Override
						public void process(Exchange exchange) throws Exception {
							IncomingMessage telegramMessage = exchange.getIn().getBody(IncomingMessage.class);
							String message = telegramMessage.getText();
							exchange.getIn().setHeader("TelegramRequest", message);
						}
					})
					
					/*
					 * Alternative way to save a message as 
					 * */
					//.setHeader("TelegramRequest", bodyAs(String.class))
					
					/*
					 * Lookup contact in Salesforce which FirstName and LastName equals Telegram message 
					 */
					.toD("salesforce:query?sObjectQuery=SELECT Id, MailingCity FROM Contact WHERE FirstName=\'${body.from.firstName}\' AND LastName=\'${body.from.lastName}\'&sObjectClass=org.apache.camel.salesforce.dto.QueryRecordsContact")
					
					/*
					 * Filter out (stop message processing) if no Contact found in Salesforce
					 * */
					.filter(new Predicate() {
						@Override
						public boolean matches(Exchange exchange) {
							QueryRecordsContact foundContacts = exchange.getIn().getBody(QueryRecordsContact.class);
							boolean filterResult = foundContacts.getRecords().size() == 1;
							if (filterResult)
								exchange.getIn().setHeader("Contact", foundContacts.getRecords().get(0));
							return filterResult;
						}
					})
					
					/*
					 * Alternative way to filter message (use lambda) 
					 * */
					//.filter(e -> e.getIn().getBody(QueryRecordsContact.class).getRecords().size() == 1)
					//.setHeader("Contact", bodyAs(QueryRecordsContact.class).getRecords().get(0))
					
					/*
					 * If found Contact has city information code in the address - find weather for this address
					 */
					.choice()
					
						.when(new Predicate() {
							@Override
							public boolean matches(Exchange exchange) {
								QueryRecordsContact foundContacts = exchange.getIn().getBody(QueryRecordsContact.class);
								Contact contact = foundContacts.getRecords().get(0);
								return contact.getMailingCity() != null;
							}	
						})
						
						/*
						 * Request weather for the Contact location 
						 * */
						.toD("weather:get?location=${body.records[0].getMailingCity}&appid=dd2e852cc0b1adbf9f9f975f4ff1b50e")
						
						
						/*
						 * Saves temperature for the location
						 * */
						.process(new Processor() {
							@Override
							public void process(Exchange exchange) throws Exception {
								ObjectMapper objectMapper = new ObjectMapper();
								JsonNode weatherInfo = objectMapper.readTree(exchange.getIn().getBody(String.class));
								Double temperature = weatherInfo.get("main").get("temp").asDouble();
								exchange.getIn().setHeader("Temperature", temperature);
							}				
						})
					
					/*
					 * End if
					 */
					.end()
					
					/*
					 * Prepares the case to be created in Salesforce
					 * */
					.process(new Processor() {
						@Override
						public void process(Exchange exchange) throws Exception {
							Contact contact = exchange.getIn().getHeader("Contact", Contact.class);
							String chatId = exchange.getIn().getHeader("CamelTelegramChatId", String.class);
							Double temperature = exchange.getIn().getHeader("Temperature", Double.class);
							boolean highTemperature = (temperature != null) && (temperature.doubleValue() < 276);
							
							Case newCase = new Case();
							newCase.setContactId(contact.getId());
							newCase.setTelegram_Chat_Id__c(chatId);
							newCase.setSubject((String)exchange.getIn().getHeader("TelegramRequest"));
							newCase.setPriority(highTemperature ? Case_PriorityEnum.HIGH : Case_PriorityEnum.LOW);
							exchange.getIn().setBody(newCase);
						}
					})
					
					/*
					 * Create new case in Salesforce 
					 */
					.to("salesforce:createSObject?sObjectClass=org.apache.camel.salesforce.dto.Case")
					
					/*
					 * Prepares final response to Telegram Chat
					 * */
					.process(new Processor() {
						@Override
						public void process(Exchange exchange) throws Exception {
							CreateSObjectResult newCase = exchange.getIn().getBody(CreateSObjectResult.class);
							OutgoingTextMessage messageOut = new OutgoingTextMessage();
							messageOut.setText("Created case id " + newCase.getId());
							exchange.getIn().setBody(messageOut);
						}
					})
					
					/*
					 * Writes message back to user
					 * */
					.to("telegram:bots/823983250:AAEcFgnNwMFfR0ENcVEU46sK11xHpEkzptM")
				;
			}
		});
		context.start();
		Thread.sleep(1000 * 60 * 24 * 7);
	}
}
