/*
 * Copyright (C) 2013-2015 Uncharted Software Inc.
 *
 * Property of Uncharted(TM), formerly Oculus Info Inc.
 * http://uncharted.software/
 *
 * Released under the MIT License.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package influent.server.utilities;

import influent.idl.*;
import influent.idlhelper.PropertyHelper;
import influent.server.data.ChartData;
import influent.server.dataaccess.DataAccessHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

import org.apache.avro.AvroRemoteException;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ChartBuilder {
	private static final Logger s_logger = LoggerFactory.getLogger(ChartBuilder.class);
	private final FL_LinkSearch transactionsSearcher;
	private final FL_ClusteringDataAccess da;
	private final Cache chartDataCache;
	private boolean bDebugChartData = false;
	
	public ChartBuilder(FL_LinkSearch transactionsSearcher, FL_ClusteringDataAccess da,  String ehCacheConfig) {
		this.transactionsSearcher = transactionsSearcher;
		this.da = da;
		
		CacheManager cacheManager = null;
		if (ehCacheConfig != null) {
			cacheManager = CacheManager.create(ehCacheConfig);
		} else {
			s_logger.error("ehcache property not set, chart data won't be cached");
			chartDataCache=null;
			return;
		}
		chartDataCache = cacheManager.getCache("ChartDataCache");
	}
	
	public ChartData computeChart(
		FL_DateRange dateRange,
		List<String> entities,
		List<String> focusEntities,
		String contextId,
		String focusContextId,
		String sessionId,
		Integer bucketNo,
		ChartHash hash
	) throws AvroRemoteException {
		Double startingBalance = 0.0;
		
		String units = null;
		
		List<Double> credits = new ArrayList<Double>(bucketNo);
		List<Double> debits = new ArrayList<Double>(bucketNo);
		List<Double> focusCredits = new ArrayList<Double>(bucketNo);
		List<Double> focusDebits = new ArrayList<Double>(bucketNo);
		for (int i = 0; i < bucketNo; i++) {
			credits.add(0.0);
			debits.add(0.0);
			focusCredits.add(0.0);
			focusDebits.add(0.0);
		}
		
		boolean foundInCache = false;
		
		if (chartDataCache != null) {
			Element element = chartDataCache.get(hash.getHash());
			if (element == null) {
				foundInCache = false;
			} else {
				CachedChartData data = (CachedChartData)element.getObjectValue();
				units = data.units;
				credits = data.credits;
				debits = data.debits;
				focusCredits = data.focusCredits;
				focusDebits = data.focusDebits;
				foundInCache = true;
			}
		}

		if (!foundInCache) startingBalance = calcStartingBalance(DateTimeParser.fromFL(dateRange.getStartDate()), entities); 
		Double runningBalance = startingBalance;
		Double maxBalance = startingBalance;
		Double minBalance = startingBalance;

		if (!foundInCache) {
			Map<String, List<FL_Link>> links = da.getTimeSeriesAggregation(entities, focusEntities, dateRange, contextId, focusContextId);
			if (links != null && links.size() > 0) {
				
				for (String entity : links.keySet()) {
					List<FL_Link> financialLinks = links.get(entity);
					for (FL_Link financialLink : financialLinks) {			
						int bucket = 0;
						Double amount = 0.0;
						boolean skip = false;
						for (FL_Property prop : financialLink.getProperties()) {
							PropertyHelper property = PropertyHelper.from(prop);
							if (property.hasTag(FL_PropertyTag.DATE)) {
								Long date = (Long)property.getValue();
								bucket = DateRangeBuilder.determineInterval(DateTimeParser.fromFL(date), DateTimeParser.fromFL(dateRange.getStartDate()), dateRange.getDurationPerBin().getInterval(), dateRange.getDurationPerBin().getNumIntervals().intValue());
								if (bucket >= bucketNo) skip=true;
								if (bucket < 0) skip=true;
							}
							
							if (property.hasTag(FL_PropertyTag.AMOUNT)) {
								amount = (Double)property.getValue();
							}
						}
						if (!skip) {
							boolean focusOverride = false;
							String sourceId = financialLink.getSource();
							String targetId = financialLink.getTarget();
							if ((sourceId != null && focusEntities != null && focusEntities.contains(sourceId)) || (targetId != null && focusEntities != null && focusEntities.contains(targetId))) {
								focusOverride = true;
							}
							if (financialLink.getDirected()) {
								if (sourceId.equals(entity)) {
									focusDebits.set(bucket, focusDebits.get(bucket) + (amount == null ? 0.0 : amount));
								} else {
									focusCredits.set(bucket, focusCredits.get(bucket) + (amount == null ? 0.0 : amount));
								}
							} else {
								if (focusOverride) {
									if (sourceId != null) {
										focusDebits.set(bucket, focusDebits.get(bucket) + (amount == null ? 0.0 : amount));
									} else {
										focusCredits.set(bucket, focusCredits.get(bucket) + (amount == null ? 0.0 : amount));
									}
								}
								if (sourceId != null) {
									debits.set(bucket, debits.get(bucket) + (amount == null ? 0.0 : amount));
								} else {
									credits.set(bucket, credits.get(bucket) + (amount == null ? 0.0 : amount));
								}
							}
						}
					}
	
				}
			}
		}

		FL_PropertyDescriptors searchDescriptors = transactionsSearcher.getDescriptors();
		for (FL_PropertyDescriptor pd :searchDescriptors.getProperties()) {
			if (pd.getKey().equals(FL_RequiredPropertyKey.AMOUNT.name())) {
				units = pd.getFriendlyText();
				break;
			}
		}
		
		for (int i=0; i<bucketNo; i++) {
			runningBalance = runningBalance + credits.get(i) - debits.get(i);
			maxBalance = (runningBalance > maxBalance) ? runningBalance : maxBalance;
			minBalance = (runningBalance < minBalance) ? runningBalance : minBalance;
		}
		
		Double endBalance = runningBalance;

		Double maxCredit = 0.0;
		Double maxDebit = 0.0;
		for (int i=0; i<bucketNo; i++) {
			maxCredit = (credits.get(i) > maxCredit) ? credits.get(i) : maxCredit;
			maxDebit = (debits.get(i) > maxDebit) ? debits.get(i) : maxDebit;
		}
		
		if (bDebugChartData) {
			 System.out.print("Chart Data #####################################\n C:");
			 for (Double credit : credits) { System.out.print(credit+","); }
			 System.out.print("\n D:");
			 for (Double debit : debits) { System.out.print(debit+","); }
			 System.out.print("\nFC:");
			 for (Double credit : focusCredits) { System.out.print(credit+","); }
			 System.out.print("\nFD:");
			 for (Double debit : focusDebits) { System.out.print(debit+","); }
			 System.out.println("\n##############################################");
		}
		
		if (!foundInCache && chartDataCache != null) {
			CachedChartData data = new CachedChartData();
			data.setUnits(units);
			data.setCredits(credits);
			data.setDebits(debits);
			data.setFocusCredits(focusCredits);
			data.setFocusDebits(focusDebits);
			data.setStartingBalance(0.0);
			Element element = new Element(hash.getHash(), data);
			chartDataCache.put(element);
		}
		
		return new ChartData(startingBalance, endBalance, units, credits, debits, maxCredit, maxDebit, maxBalance, minBalance, focusCredits, focusDebits, DataAccessHelper.getDateIntervals(dateRange), hash.getHash());
	}
	
	private Double calcStartingBalance(DateTime minDate, List<String> memberIds) {
//		final DateTime epoch = new DateTime(1970,1,1,0,0);
		Double balance = 0.0;
		
//		Map<String, List<Link>> links = null;// da.getRelatedLinks(memberIds, EntityLinkType.BOTH, filters, AggregationType.TIME_SERIES, null,null);
//		if (links != null && links.size() > 0) {
//			for (String linkType : links.keySet()) {
//				List<Link> financialLinks = links.get(linkType);
//				for (Link financialLink : financialLinks) {
//					Double amount = (Double)financialLink.getFirstProperty(PropertyTag.AMOUNT).getValue();
//					if (memberIds.contains(financialLink.getSourceEntityId())) {
//						balance = balance - amount;
//					} else {
//						balance = balance + amount;
//					}
//				}
//			}
//		}
		return balance;
	}

}
