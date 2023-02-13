package br.com.fta.transaction.application;

import br.com.fta.model.Frauds;
import br.com.fta.shared.exceptions.ResourceNotFoundException;
import br.com.fta.transaction.domain.ImportInfo;
import br.com.fta.transaction.domain.InvalidFileException;
import br.com.fta.transaction.domain.Transaction;
import br.com.fta.transaction.domain.TransactionAnalyzer;
import br.com.fta.transaction.infra.ImportInfoRepository;
import br.com.fta.transaction.infra.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

@Service
public class TransactionService {

	@Autowired
	private TransactionRepository transactionRepository;
	@Autowired
	private ImportInfoRepository importInfoRepository;
	@Autowired
	private TransactionAnalyzer analyzer;
	@Autowired
	private FraudClient fraudClient;

	@Autowired
	private GeneratorClient generatorClient;

	public Page<ImportInfo> transactions(PageRequest pageRequest) {
		pageRequest.withSort(Sort.sort(ImportInfo.class).descending());
		return importInfoRepository.findAll(pageRequest);
	}

	public void postTransaction(MultipartFile file, String username) {
		if (file.isEmpty()) {
			throw new InvalidFileException("Empty file.");
		}

		Set<Transaction> transaction = analyzer.analyzeTransaction(file);

		ImportInfo importInfo = new ImportInfo(
										LocalDateTime.now(),
										analyzer.getDateOfTransactions(),
										username);
		transactionRepository.saveAll(transaction);
		importInfoRepository.save(importInfo);
	}

	public void deleteTransactions() {
		transactionRepository.deleteAll();
		importInfoRepository.deleteAll();
	}

	public Page<Transaction> detailTransactions(LocalDate date, PageRequest pageRequest) {
		LocalDateTime startDay = date.atStartOfDay();
		LocalDateTime endDay = date.atTime(23, 59, 59);

		return transactionRepository.findByDateBetween(startDay, endDay, pageRequest);
	}

	public ImportInfo detailImport(String dateString) {
		try {
			LocalDate date = LocalDate.parse(dateString);

			Optional<ImportInfo> importInfoOptional = importInfoRepository.findByTransactionsDate(date);
			return importInfoOptional.get();
		}
		catch (DateTimeParseException | NoSuchElementException e) {
			throw new ResourceNotFoundException();
		}
	}

	public void report(String dateString, Model model) {
		if (dateString == null) {
			return;
		}

		try {
			// 2022-01-01
			LocalDate startOfMonth = LocalDate.parse(dateString + "-01", DateTimeFormatter.ISO_DATE);
			LocalDateTime start = LocalDateTime.of(startOfMonth, LocalTime.of(0, 0));

			LocalDate endOfMonth = LocalDate.of(
					startOfMonth.getYear(), startOfMonth.getMonthValue(), startOfMonth.lengthOfMonth());
			LocalDateTime end = LocalDateTime.of(endOfMonth, LocalTime.of(23, 59));

			Optional<List<Transaction>> list = transactionRepository.findByDateBetween(start, end);
			List<Transaction> transactions = list.get();

			model.addAttribute("date", start);
			if (transactions.isEmpty()) {
				throw new ResourceNotFoundException();
			}

			Frauds frauds = fraudClient.detectFrauds(transactions);

			model.addAttribute("transactions", frauds.fraudTransactions());
			model.addAttribute("accounts", frauds.fraudAccounts());
			model.addAttribute("agencies", frauds.fraudAgencies());

			model.addAttribute("noTransactions", false);
		} catch (RuntimeException e) {
			model.addAttribute("noTransactions", true);
		}
	}

	public void generateTransactions(String username) {
		List<Transaction> list = generatorClient.generateTransactions();
		transactionRepository.saveAll(list);

		ImportInfo importInfo = new ImportInfo(
				LocalDateTime.now(),
				list.get(0).getDate().toLocalDate(),
				username);

		importInfoRepository.save(importInfo);
	}
}