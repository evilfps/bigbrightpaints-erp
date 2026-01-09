package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.CompanyDefaultAccountsResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingAccountSuggestionsResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingOpeningStockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingOpeningStockResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingPartnerOpeningBalanceRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingPartnerOpeningBalanceResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingRawMaterialRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionCategory;
import com.bigbrightpaints.erp.modules.production.domain.ProductionCategoryRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantRequest;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantResponse;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.ProductionBrandRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionCategoryDto;
import com.bigbrightpaints.erp.modules.production.dto.ProductionCategoryRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierResponse;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerResponse;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static com.bigbrightpaints.erp.modules.accounting.service.AbstractPartnerLedgerService.LedgerContext;

@Service
public class OnboardingService {

    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern NON_SKU_CHAR = Pattern.compile("[^A-Z0-9-]");
    private static final List<String> RAW_MATERIAL_CATEGORIES = List.of("RAW_MATERIAL", "RAW MATERIAL", "RAW-MATERIAL");
    private static final int ACCOUNT_SUGGESTION_LIMIT = 25;

    private final CompanyContextService companyContextService;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final CompanyClock companyClock;
    private final AuditService auditService;
    private final ProductionCatalogService productionCatalogService;
    private final ProductionBrandRepository brandRepository;
    private final ProductionCategoryRepository categoryRepository;
    private final ProductionProductRepository productRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final RawMaterialService rawMaterialService;
    private final FinishedGoodsService finishedGoodsService;
    private final SupplierService supplierService;
    private final SupplierRepository supplierRepository;
    private final DealerService dealerService;
    private final DealerRepository dealerRepository;
    private final AccountingService accountingService;
    private final JournalEntryRepository journalEntryRepository;
    private final DealerLedgerService dealerLedgerService;
    private final DealerLedgerRepository dealerLedgerRepository;
    private final SupplierLedgerService supplierLedgerService;
    private final SupplierLedgerRepository supplierLedgerRepository;
    private final AccountRepository accountRepository;

    public OnboardingService(CompanyContextService companyContextService,
                             CompanyEntityLookup companyEntityLookup,
                             CompanyDefaultAccountsService companyDefaultAccountsService,
                             CompanyClock companyClock,
                             AuditService auditService,
                             ProductionCatalogService productionCatalogService,
                             ProductionBrandRepository brandRepository,
                             ProductionCategoryRepository categoryRepository,
                             ProductionProductRepository productRepository,
                             FinishedGoodRepository finishedGoodRepository,
                             FinishedGoodBatchRepository finishedGoodBatchRepository,
                             InventoryMovementRepository inventoryMovementRepository,
                             RawMaterialRepository rawMaterialRepository,
                             RawMaterialBatchRepository rawMaterialBatchRepository,
                             RawMaterialMovementRepository rawMaterialMovementRepository,
                             RawMaterialService rawMaterialService,
                             FinishedGoodsService finishedGoodsService,
                             SupplierService supplierService,
                             SupplierRepository supplierRepository,
                             DealerService dealerService,
                             DealerRepository dealerRepository,
                             AccountingService accountingService,
                             JournalEntryRepository journalEntryRepository,
                             DealerLedgerService dealerLedgerService,
                             DealerLedgerRepository dealerLedgerRepository,
                             SupplierLedgerService supplierLedgerService,
                             SupplierLedgerRepository supplierLedgerRepository,
                             AccountRepository accountRepository) {
        this.companyContextService = companyContextService;
        this.companyEntityLookup = companyEntityLookup;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.companyClock = companyClock;
        this.auditService = auditService;
        this.productionCatalogService = productionCatalogService;
        this.brandRepository = brandRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.rawMaterialService = rawMaterialService;
        this.finishedGoodsService = finishedGoodsService;
        this.supplierService = supplierService;
        this.supplierRepository = supplierRepository;
        this.dealerService = dealerService;
        this.dealerRepository = dealerRepository;
        this.accountingService = accountingService;
        this.journalEntryRepository = journalEntryRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.dealerLedgerRepository = dealerLedgerRepository;
        this.supplierLedgerService = supplierLedgerService;
        this.supplierLedgerRepository = supplierLedgerRepository;
        this.accountRepository = accountRepository;
    }

    public List<ProductionBrandDto> listBrands() {
        return productionCatalogService.listBrands();
    }

    @Transactional
    public ProductionBrandDto upsertBrand(ProductionBrandRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String name = requireText(request.name(), "Brand name");
        Optional<ProductionBrand> existing = findBrand(company, request.code(), name);
        if (existing.isPresent()) {
            return toBrandDto(existing.get());
        }
        String code = nextCode(company, sanitizeCode(StringUtils.hasText(request.code()) ? request.code() : name), 12, brandRepository);
        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(company);
        brand.setName(name);
        brand.setCode(code);
        brand.setDescription(normalizeOptional(request.description()));
        ProductionBrand saved = brandRepository.save(brand);
        auditService.logDataAccess("production_brand", saved.getId().toString(), "CREATE");
        return toBrandDto(saved);
    }

    @Transactional
    public ProductionBrandDto updateBrand(Long brandId, ProductionBrandRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionBrand brand = companyEntityLookup.requireProductionBrand(company, brandId);
        if (StringUtils.hasText(request.name())) {
            brand.setName(request.name().trim());
        }
        if (StringUtils.hasText(request.code())) {
            String code = sanitizeCode(request.code());
            Optional<ProductionBrand> conflict = brandRepository.findByCompanyAndCodeIgnoreCase(company, code);
            if (conflict.isPresent() && !Objects.equals(conflict.get().getId(), brand.getId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Brand code already exists: " + code);
            }
            brand.setCode(code);
        }
        if (request.description() != null) {
            brand.setDescription(normalizeOptional(request.description()));
        }
        ProductionBrand saved = brandRepository.save(brand);
        auditService.logDataAccess("production_brand", saved.getId().toString(), "UPDATE");
        return toBrandDto(saved);
    }

    public List<ProductionCategoryDto> listCategories() {
        Company company = companyContextService.requireCurrentCompany();
        return categoryRepository.findByCompanyOrderByNameAsc(company).stream()
                .map(this::toCategoryDto)
                .toList();
    }

    @Transactional
    public ProductionCategoryDto upsertCategory(ProductionCategoryRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String name = requireText(request.name(), "Category name");
        Optional<ProductionCategory> existing = findCategory(company, request.code(), name);
        if (existing.isPresent()) {
            return toCategoryDto(existing.get());
        }
        String code = nextCode(company, normalizeCategoryCode(StringUtils.hasText(request.code()) ? request.code() : name),
                24, categoryRepository);
        ProductionCategory category = new ProductionCategory();
        category.setCompany(company);
        category.setName(name);
        category.setCode(code);
        category.setDescription(normalizeOptional(request.description()));
        ProductionCategory saved = categoryRepository.save(category);
        auditService.logDataAccess("production_category", saved.getId().toString(), "CREATE");
        return toCategoryDto(saved);
    }

    @Transactional
    public ProductionCategoryDto updateCategory(Long categoryId, ProductionCategoryRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionCategory category = categoryRepository.findByCompanyAndId(company, categoryId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Category not found"));
        if (StringUtils.hasText(request.name())) {
            category.setName(request.name().trim());
        }
        if (StringUtils.hasText(request.code())) {
            String code = normalizeCategoryCode(request.code());
            Optional<ProductionCategory> conflict = categoryRepository.findByCompanyAndCodeIgnoreCase(company, code);
            if (conflict.isPresent() && !Objects.equals(conflict.get().getId(), category.getId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Category code already exists: " + code);
            }
            category.setCode(code);
        }
        if (request.description() != null) {
            category.setDescription(normalizeOptional(request.description()));
        }
        ProductionCategory saved = categoryRepository.save(category);
        auditService.logDataAccess("production_category", saved.getId().toString(), "UPDATE");
        return toCategoryDto(saved);
    }

    public List<ProductionProductDto> listProducts() {
        return productionCatalogService.listProducts();
    }

    @Transactional
    public ProductionProductDto upsertProduct(ProductCreateRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ensureCategoryExists(company, request.category());
        requireProductDefaultsAndMetadata(company, request.category(), request.metadata());
        ProductionProduct existing = resolveExistingProduct(company, request);
        if (existing != null) {
            ensureFinishedGood(existing);
            return toProductDto(existing);
        }
        try {
            ProductionProductDto created = productionCatalogService.createProduct(request);
            ProductionProduct product = productRepository.findByCompanyAndId(company, created.id())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Product creation failed for " + created.skuCode()));
            ensureFinishedGood(product);
            auditService.logDataAccess("production_product", product.getId().toString(), "CREATE");
            return created;
        } catch (IllegalStateException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, ex.getMessage());
        }
    }

    @Transactional
    public ProductionProductDto updateProduct(Long productId, ProductUpdateRequest request) {
        try {
            ProductionProductDto updated = productionCatalogService.updateProduct(productId, request);
            ProductionProduct product = productRepository.findById(updated.id())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Product not found for update"));
            ensureFinishedGood(product);
            auditService.logDataAccess("production_product", product.getId().toString(), "UPDATE");
            return updated;
        } catch (IllegalStateException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, ex.getMessage());
        }
    }

    @Transactional
    public BulkVariantResponse createVariants(BulkVariantRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ensureCategoryExists(company, request.category());
        requireProductDefaultsAndMetadata(company, request.category(), request.metadata());
        try {
            BulkVariantResponse response = productionCatalogService.createVariants(request);
            for (ProductionProductDto variant : response.variants()) {
                productRepository.findByCompanyAndId(company, variant.id()).ifPresent(this::ensureFinishedGood);
            }
            return response;
        } catch (IllegalStateException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, ex.getMessage());
        }
    }

    public List<com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto> listRawMaterials() {
        return rawMaterialService.listRawMaterials();
    }

    @Transactional
    public com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto upsertRawMaterial(OnboardingRawMaterialRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String sku = requireText(request.sku(), "Raw material SKU");
        Optional<RawMaterial> existing = rawMaterialRepository.findByCompanyAndSku(company, sku);
        if (existing.isPresent()) {
            return rawMaterialService.getRawMaterial(existing.get().getId());
        }
        Long inventoryAccountId = resolveInventoryAccount(company, request.inventoryAccountId());
        RawMaterialRequest payload = new RawMaterialRequest(
                requireText(request.name(), "Raw material name"),
                sku,
                requireText(request.unitType(), "Raw material unit"),
                safeNumber(request.reorderLevel()),
                safeNumber(request.minStock()),
                safeNumber(request.maxStock()),
                inventoryAccountId
        );
        com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto created = rawMaterialService.createRawMaterial(payload);
        rawMaterialRepository.findByCompanyAndId(company, created.id()).ifPresent(material -> {
            if (request.materialType() != null) {
                material.setMaterialType(request.materialType());
                rawMaterialRepository.save(material);
            }
        });
        auditService.logDataAccess("raw_material", created.id().toString(), "CREATE");
        return created;
    }

    @Transactional
    public com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto updateRawMaterial(Long id,
                                                                                          OnboardingRawMaterialRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Long inventoryAccountId = resolveInventoryAccount(company, request.inventoryAccountId());
        RawMaterialRequest payload = new RawMaterialRequest(
                requireText(request.name(), "Raw material name"),
                requireText(request.sku(), "Raw material SKU"),
                requireText(request.unitType(), "Raw material unit"),
                safeNumber(request.reorderLevel()),
                safeNumber(request.minStock()),
                safeNumber(request.maxStock()),
                inventoryAccountId
        );
        com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto updated = rawMaterialService.updateRawMaterial(id, payload);
        rawMaterialRepository.findByCompanyAndId(company, updated.id()).ifPresent(material -> {
            if (request.materialType() != null) {
                material.setMaterialType(request.materialType());
                rawMaterialRepository.save(material);
            }
        });
        auditService.logDataAccess("raw_material", updated.id().toString(), "UPDATE");
        return updated;
    }

    public List<SupplierResponse> listSuppliers() {
        return supplierService.listSuppliers();
    }

    @Transactional
    public SupplierResponse upsertSupplier(SupplierRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Optional<Supplier> existing = findSupplier(company, request.code(), request.name());
        if (existing.isPresent()) {
            return supplierService.getSupplier(existing.get().getId());
        }
        SupplierResponse created = supplierService.createSupplier(request);
        auditService.logDataAccess("supplier", created.id().toString(), "CREATE");
        return created;
    }

    @Transactional
    public SupplierResponse updateSupplier(Long id, SupplierRequest request) {
        SupplierResponse updated = supplierService.updateSupplier(id, request);
        auditService.logDataAccess("supplier", id.toString(), "UPDATE");
        return updated;
    }

    public List<DealerResponse> listDealers() {
        return dealerService.listDealers();
    }

    public OnboardingAccountSuggestionsResponse accountSuggestions() {
        Company company = companyContextService.requireCurrentCompany();
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company).stream()
                .filter(Account::isActive)
                .toList();
        CompanyDefaultAccountsService.DefaultAccounts defaults = companyDefaultAccountsService.getDefaults();
        CompanyDefaultAccountsResponse defaultsResponse = new CompanyDefaultAccountsResponse(
                defaults.inventoryAccountId(),
                defaults.cogsAccountId(),
                defaults.revenueAccountId(),
                defaults.discountAccountId(),
                defaults.taxAccountId()
        );
        return new OnboardingAccountSuggestionsResponse(
                defaultsResponse,
                candidatesByType(accounts, AccountType.ASSET),
                candidatesByType(accounts, AccountType.COGS),
                candidatesByType(accounts, AccountType.REVENUE),
                candidatesByType(accounts, AccountType.LIABILITY),
                candidatesByTypeWithHint(accounts, AccountType.ASSET, "WIP"),
                candidatesByTypeWithHint(accounts, AccountType.ASSET, "SEMI"),
                candidatesByTypes(accounts, List.of(AccountType.REVENUE, AccountType.EXPENSE))
        );
    }

    @Transactional
    public DealerResponse upsertDealer(CreateDealerRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String email = requireText(request.contactEmail(), "Dealer email");
        Optional<Dealer> existing = dealerRepository.findByCompanyAndEmailIgnoreCase(company, email.trim());
        if (existing.isPresent()) {
            return dealerService.getDealer(existing.get().getId());
        }
        DealerResponse created = dealerService.createDealer(request);
        auditService.logDataAccess("dealer", created.id().toString(), "CREATE");
        return created;
    }

    @Transactional
    public DealerResponse updateDealer(Long dealerId, CreateDealerRequest request) {
        DealerResponse updated = dealerService.updateDealer(dealerId, request);
        auditService.logDataAccess("dealer", dealerId.toString(), "UPDATE");
        return updated;
    }

    @Transactional
    public OnboardingOpeningStockResponse recordOpeningStock(OnboardingOpeningStockRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        List<OnboardingOpeningStockRequest.FinishedGoodLine> finishedGoods = defaultList(request.finishedGoods());
        List<OnboardingOpeningStockRequest.RawMaterialLine> rawMaterials = defaultList(request.rawMaterials());
        if (finishedGoods.isEmpty() && rawMaterials.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Opening stock requires at least one line");
        }

        String reference = openingReference("OPEN-STOCK", request.referenceNumber());
        Optional<JournalEntry> existingEntry = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existingEntry.isPresent()) {
            int rmSkipped = ensureOpeningBatchesExist(rawMaterials, company, request.referenceNumber());
            int fgSkipped = ensureFinishedGoodBatchesExist(finishedGoods, company, request.referenceNumber());
            return new OnboardingOpeningStockResponse(reference, existingEntry.get().getId(), 0, 0, rmSkipped, fgSkipped);
        }

        ensureNoOpeningBatchConflicts(rawMaterials, finishedGoods, company, request.referenceNumber());
        Long offsetAccountId = requireAccount(request.offsetAccountId(), company);
        Map<Long, BigDecimal> debitLines = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;

        for (OnboardingOpeningStockRequest.RawMaterialLine line : rawMaterials) {
            RawMaterial material = requireRawMaterial(company, line.sku());
            Long inventoryAccountId = resolveInventoryAccount(company, material.getInventoryAccountId());
            BigDecimal lineTotal = MoneyUtils.safeMultiply(requirePositive(line.quantity(), "quantity"),
                    requirePositive(line.unitCost(), "unitCost"));
            debitLines.merge(inventoryAccountId, lineTotal, BigDecimal::add);
            total = total.add(lineTotal);
        }
        for (OnboardingOpeningStockRequest.FinishedGoodLine line : finishedGoods) {
            FinishedGood finishedGood = requireFinishedGood(company, line.productCode());
            ensureFinishedGoodAccounts(finishedGood, null);
            Long inventoryAccountId = finishedGood.getValuationAccountId();
            if (inventoryAccountId == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Finished good " + finishedGood.getProductCode() + " missing valuation account");
            }
            BigDecimal lineTotal = MoneyUtils.safeMultiply(requirePositive(line.quantity(), "quantity"),
                    requirePositive(line.unitCost(), "unitCost"));
            debitLines.merge(inventoryAccountId, lineTotal, BigDecimal::add);
            total = total.add(lineTotal);
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Opening stock total must be positive");
        }

        String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "Opening stock";
        JournalEntryRequest journalPayload = buildJournal(company, reference, request.entryDate(), memo,
                debitLines, offsetAccountId, total);
        JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company,
                accountingService.createJournalEntry(journalPayload).id());

        int rmCreated = 0;
        int fgCreated = 0;
        for (OnboardingOpeningStockRequest.RawMaterialLine line : rawMaterials) {
            RawMaterial material = requireRawMaterial(company, line.sku());
            String batchCode = resolveOpeningBatchCode(request.referenceNumber(), material.getSku(), line.batchCode());
            RawMaterialBatch batch = new RawMaterialBatch();
            batch.setRawMaterial(material);
            batch.setBatchCode(batchCode);
            batch.setQuantity(requirePositive(line.quantity(), "quantity"));
            batch.setUnit(resolveUnit(line.unit(), material.getUnitType()));
            batch.setCostPerUnit(requirePositive(line.unitCost(), "unitCost"));
            batch.setSupplierName("OPENING_STOCK");
            RawMaterialBatch savedBatch = rawMaterialBatchRepository.save(batch);

            BigDecimal stock = MoneyUtils.zeroIfNull(material.getCurrentStock()).add(batch.getQuantity());
            material.setCurrentStock(stock);
            rawMaterialRepository.save(material);

            RawMaterialMovement movement = new RawMaterialMovement();
            movement.setRawMaterial(material);
            movement.setRawMaterialBatch(savedBatch);
            movement.setReferenceType(InventoryReference.OPENING_STOCK);
            movement.setReferenceId(batchCode);
            movement.setMovementType("RECEIPT");
            movement.setQuantity(batch.getQuantity());
            movement.setUnitCost(batch.getCostPerUnit());
            movement.setJournalEntryId(journalEntry.getId());
            rawMaterialMovementRepository.save(movement);
            rmCreated++;
        }

        for (OnboardingOpeningStockRequest.FinishedGoodLine line : finishedGoods) {
            FinishedGood finishedGood = requireFinishedGood(company, line.productCode());
            ensureFinishedGoodAccounts(finishedGood, null);
            String batchCode = resolveOpeningBatchCode(request.referenceNumber(), finishedGood.getProductCode(), line.batchCode());
            FinishedGoodBatch batch = new FinishedGoodBatch();
            batch.setFinishedGood(finishedGood);
            batch.setBatchCode(batchCode);
            batch.setQuantityTotal(requirePositive(line.quantity(), "quantity"));
            batch.setQuantityAvailable(requirePositive(line.quantity(), "quantity"));
            batch.setUnitCost(requirePositive(line.unitCost(), "unitCost"));
            batch.setManufacturedAt(resolveManufacturedAt(company, line.manufacturedDate()));
            FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

            BigDecimal stock = MoneyUtils.zeroIfNull(finishedGood.getCurrentStock()).add(batch.getQuantityTotal());
            finishedGood.setCurrentStock(stock);
            finishedGoodRepository.save(finishedGood);

            InventoryMovement movement = new InventoryMovement();
            movement.setFinishedGood(finishedGood);
            movement.setFinishedGoodBatch(savedBatch);
            movement.setReferenceType(InventoryReference.OPENING_STOCK);
            movement.setReferenceId(batchCode);
            movement.setMovementType("RECEIPT");
            movement.setQuantity(batch.getQuantityTotal());
            movement.setUnitCost(batch.getUnitCost());
            movement.setJournalEntryId(journalEntry.getId());
            inventoryMovementRepository.save(movement);
            fgCreated++;
        }

        auditService.logDataAccess("opening_stock", journalEntry.getId().toString(), "CREATE");
        return new OnboardingOpeningStockResponse(reference, journalEntry.getId(), rmCreated, fgCreated, 0, 0);
    }

    @Transactional
    public OnboardingPartnerOpeningBalanceResponse recordDealerOpeningBalance(OnboardingPartnerOpeningBalanceRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        List<OnboardingPartnerOpeningBalanceRequest.PartnerLine> lines = request.lines();
        if (lines == null || lines.size() != 1) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Opening receivable requires exactly one partner line per request");
        }
        String reference = openingReference("OPEN-AR", request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        int skipped = existing.isPresent() ? 1 : 0;

        OnboardingPartnerOpeningBalanceRequest.PartnerLine line = lines.get(0);
        Dealer dealer = resolveDealer(company, line.partnerId(), line.partnerCode());
        if (dealer.getReceivableAccount() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Dealer " + dealer.getName() + " is missing a receivable account");
        }
        BigDecimal amount = requirePositive(line.amount(), "amount");
        Long offsetAccountId = requireAccount(request.offsetAccountId(), company);
        String memo = resolveMemo(request.memo(), "Opening receivable for " + dealer.getName(), line.memo());
        LocalDate entryDate = resolveEntryDate(company, request.entryDate());

        JournalEntryRequest payload = new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(dealer.getReceivableAccount().getId(), memo, amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(offsetAccountId, memo, BigDecimal.ZERO, amount)
                )
        );
        Long journalId = accountingService.createJournalEntry(payload).id();
        JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company, journalId);
        if (dealerLedgerRepository.findByCompanyAndJournalEntry(company, journalEntry).isEmpty()) {
            dealerLedgerService.recordLedgerEntry(dealer, new LedgerContext(entryDate, reference, memo, amount, BigDecimal.ZERO, journalEntry));
        }
        auditService.logDataAccess("opening_receivable", journalEntry.getId().toString(), "CREATE");
        return new OnboardingPartnerOpeningBalanceResponse(reference, journalEntry.getId(), 1, skipped);
    }

    @Transactional
    public OnboardingPartnerOpeningBalanceResponse recordSupplierOpeningBalance(OnboardingPartnerOpeningBalanceRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        List<OnboardingPartnerOpeningBalanceRequest.PartnerLine> lines = request.lines();
        if (lines == null || lines.size() != 1) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Opening payable requires exactly one partner line per request");
        }
        String reference = openingReference("OPEN-AP", request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        int skipped = existing.isPresent() ? 1 : 0;

        OnboardingPartnerOpeningBalanceRequest.PartnerLine line = lines.get(0);
        Supplier supplier = resolveSupplier(company, line.partnerId(), line.partnerCode());
        if (supplier.getPayableAccount() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Supplier " + supplier.getName() + " is missing a payable account");
        }
        BigDecimal amount = requirePositive(line.amount(), "amount");
        Long offsetAccountId = requireAccount(request.offsetAccountId(), company);
        String memo = resolveMemo(request.memo(), "Opening payable for " + supplier.getName(), line.memo());
        LocalDate entryDate = resolveEntryDate(company, request.entryDate());

        JournalEntryRequest payload = new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                supplier.getId(),
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(offsetAccountId, memo, amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(supplier.getPayableAccount().getId(), memo, BigDecimal.ZERO, amount)
                )
        );
        Long journalId = accountingService.createJournalEntry(payload).id();
        JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company, journalId);
        if (supplierLedgerRepository.findByCompanyAndJournalEntry(company, journalEntry).isEmpty()) {
            supplierLedgerService.recordLedgerEntry(supplier, new LedgerContext(entryDate, reference, memo, BigDecimal.ZERO, amount, journalEntry));
        }
        auditService.logDataAccess("opening_payable", journalEntry.getId().toString(), "CREATE");
        return new OnboardingPartnerOpeningBalanceResponse(reference, journalEntry.getId(), 1, skipped);
    }

    private Optional<ProductionBrand> findBrand(Company company, String code, String name) {
        if (StringUtils.hasText(code)) {
            Optional<ProductionBrand> byCode = brandRepository.findByCompanyAndCodeIgnoreCase(company, sanitizeCode(code));
            if (byCode.isPresent()) {
                return byCode;
            }
        }
        if (StringUtils.hasText(name)) {
            return brandRepository.findByCompanyAndNameIgnoreCase(company, name.trim());
        }
        return Optional.empty();
    }

    private Optional<ProductionCategory> findCategory(Company company, String code, String name) {
        if (StringUtils.hasText(code)) {
            Optional<ProductionCategory> byCode = categoryRepository.findByCompanyAndCodeIgnoreCase(company, normalizeCategoryCode(code));
            if (byCode.isPresent()) {
                return byCode;
            }
        }
        if (StringUtils.hasText(name)) {
            return categoryRepository.findByCompanyAndNameIgnoreCase(company, name.trim());
        }
        return Optional.empty();
    }

    private Optional<Supplier> findSupplier(Company company, String code, String name) {
        if (StringUtils.hasText(code)) {
            Optional<Supplier> byCode = supplierRepository.findByCompanyAndCodeIgnoreCase(company, code.trim());
            if (byCode.isPresent()) {
                return byCode;
            }
        }
        if (StringUtils.hasText(name)) {
            return supplierRepository.findByCompanyAndNameIgnoreCase(company, name.trim());
        }
        return Optional.empty();
    }

    private void ensureCategoryExists(Company company, String category) {
        if (!StringUtils.hasText(category)) {
            return;
        }
        String normalized = normalizeCategoryCode(category);
        if (categoryRepository.findByCompanyAndCodeIgnoreCase(company, normalized).isPresent()) {
            return;
        }
        ProductionCategory created = new ProductionCategory();
        created.setCompany(company);
        created.setCode(normalized);
        created.setName(category.trim());
        categoryRepository.save(created);
    }

    private ProductionProduct resolveExistingProduct(Company company, ProductCreateRequest request) {
        String customSku = normalizeSku(request.customSkuCode());
        if (StringUtils.hasText(customSku)) {
            Optional<ProductionProduct> bySku = productRepository.findByCompanyAndSkuCode(company, customSku);
            if (bySku.isPresent()) {
                return bySku.get();
            }
        }
        if (!StringUtils.hasText(request.productName())) {
            return null;
        }
        ProductionBrand brand = resolveBrandForLookup(company, request);
        if (brand == null) {
            return null;
        }
        return productRepository.findByBrandAndProductNameIgnoreCase(brand, request.productName().trim())
                .orElse(null);
    }

    private ProductionBrand resolveBrandForLookup(Company company, ProductCreateRequest request) {
        if (request.brandId() != null) {
            return companyEntityLookup.requireProductionBrand(company, request.brandId());
        }
        if (StringUtils.hasText(request.brandCode())) {
            Optional<ProductionBrand> byCode = brandRepository.findByCompanyAndCodeIgnoreCase(company, request.brandCode().trim());
            if (byCode.isPresent()) {
                return byCode.get();
            }
        }
        if (StringUtils.hasText(request.brandName())) {
            return brandRepository.findByCompanyAndNameIgnoreCase(company, request.brandName().trim())
                    .orElse(null);
        }
        return null;
    }

    private void ensureFinishedGood(ProductionProduct product) {
        if (product == null) {
            return;
        }
        if (isRawMaterialCategory(product.getCategory())) {
            return;
        }
        Company company = product.getCompany();
        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, product.getSkuCode())
                .orElseGet(() -> {
                    FinishedGoodRequest request = new FinishedGoodRequest(
                            product.getSkuCode(),
                            product.getProductName(),
                            product.getUnitOfMeasure(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    );
                    finishedGoodsService.createFinishedGood(request);
                    return finishedGoodRepository.findByCompanyAndProductCode(company, product.getSkuCode())
                            .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                                    "Finished good creation failed for " + product.getSkuCode()));
                });
        ensureFinishedGoodAccounts(finishedGood, product.getMetadata());
    }

    private void ensureFinishedGoodAccounts(FinishedGood finishedGood, Map<String, Object> metadata) {
        Company company = finishedGood.getCompany();
        boolean updated = false;
        if (metadata != null && !metadata.isEmpty()) {
            updated |= applyAccountIfPresent(company, finishedGood::getValuationAccountId, finishedGood::setValuationAccountId,
                    metadata, "fgValuationAccountId");
            updated |= applyAccountIfPresent(company, finishedGood::getCogsAccountId, finishedGood::setCogsAccountId,
                    metadata, "fgCogsAccountId");
            updated |= applyAccountIfPresent(company, finishedGood::getRevenueAccountId, finishedGood::setRevenueAccountId,
                    metadata, "fgRevenueAccountId");
            updated |= applyAccountIfPresent(company, finishedGood::getDiscountAccountId, finishedGood::setDiscountAccountId,
                    metadata, "fgDiscountAccountId");
            updated |= applyAccountIfPresent(company, finishedGood::getTaxAccountId, finishedGood::setTaxAccountId,
                    metadata, "fgTaxAccountId");
        }
        if (finishedGood.getValuationAccountId() == null
                || finishedGood.getCogsAccountId() == null
                || finishedGood.getRevenueAccountId() == null
                || finishedGood.getTaxAccountId() == null) {
            var defaults = companyDefaultAccountsService.requireDefaults();
            if (finishedGood.getValuationAccountId() == null) {
                finishedGood.setValuationAccountId(requireActiveAccount(company, defaults.inventoryAccountId(),
                        "defaultInventoryAccountId").getId());
                updated = true;
            }
            if (finishedGood.getCogsAccountId() == null) {
                finishedGood.setCogsAccountId(requireActiveAccount(company, defaults.cogsAccountId(),
                        "defaultCogsAccountId").getId());
                updated = true;
            }
            if (finishedGood.getRevenueAccountId() == null) {
                finishedGood.setRevenueAccountId(requireActiveAccount(company, defaults.revenueAccountId(),
                        "defaultRevenueAccountId").getId());
                updated = true;
            }
            if (finishedGood.getDiscountAccountId() == null && defaults.discountAccountId() != null) {
                finishedGood.setDiscountAccountId(requireActiveAccount(company, defaults.discountAccountId(),
                        "defaultDiscountAccountId").getId());
                updated = true;
            }
            if (finishedGood.getTaxAccountId() == null) {
                finishedGood.setTaxAccountId(requireActiveAccount(company, defaults.taxAccountId(),
                        "defaultTaxAccountId").getId());
                updated = true;
            }
        }
        if (updated) {
            finishedGoodRepository.save(finishedGood);
        }
    }

    private boolean applyAccountIfPresent(Company company,
                                          java.util.function.Supplier<Long> getter,
                                          java.util.function.Consumer<Long> setter,
                                          Map<String, Object> metadata,
                                          String key) {
        if (getter.get() != null || metadata == null) {
            return false;
        }
        Long value = parseLong(metadata.get(key));
        if (value == null || value <= 0) {
            return false;
        }
        requireActiveAccount(company, value, key);
        setter.accept(value);
        return true;
    }

    private String openingReference(String prefix, String reference) {
        if (!StringUtils.hasText(reference)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Reference number is required");
        }
        String cleaned = reference.trim().toUpperCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("[^A-Z0-9-]", "-");
        cleaned = cleaned.replaceAll("-{2,}", "-");
        return prefix + "-" + cleaned;
    }

    private JournalEntryRequest buildJournal(Company company,
                                             String reference,
                                             LocalDate entryDate,
                                             String memo,
                                             Map<Long, BigDecimal> debitLines,
                                             Long offsetAccountId,
                                             BigDecimal total) {
        LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        debitLines.forEach((accountId, amount) -> {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new JournalEntryRequest.JournalLineRequest(accountId, memo, amount, BigDecimal.ZERO));
            }
        });
        lines.add(new JournalEntryRequest.JournalLineRequest(offsetAccountId, memo, BigDecimal.ZERO, total));
        return new JournalEntryRequest(reference, postingDate, memo, null, null, Boolean.FALSE, lines);
    }

    private int ensureOpeningBatchesExist(List<OnboardingOpeningStockRequest.RawMaterialLine> rawMaterials,
                                          Company company,
                                          String referenceNumber) {
        int skipped = 0;
        for (OnboardingOpeningStockRequest.RawMaterialLine line : rawMaterials) {
            RawMaterial material = requireRawMaterial(company, line.sku());
            String batchCode = resolveOpeningBatchCode(referenceNumber, material.getSku(), line.batchCode());
            RawMaterialBatch batch = rawMaterialBatchRepository.findByRawMaterialAndBatchCode(material, batchCode)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Opening stock reference already posted; missing batch " + batchCode));
            BigDecimal quantity = requirePositive(line.quantity(), "quantity");
            BigDecimal unitCost = requirePositive(line.unitCost(), "unitCost");
            if (batch.getQuantity().compareTo(quantity) != 0 || batch.getCostPerUnit().compareTo(unitCost) != 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Opening stock idempotency conflict for raw material " + material.getSku() + " batch " + batchCode);
            }
            skipped++;
        }
        return skipped;
    }

    private int ensureFinishedGoodBatchesExist(List<OnboardingOpeningStockRequest.FinishedGoodLine> finishedGoods,
                                               Company company,
                                               String referenceNumber) {
        int skipped = 0;
        for (OnboardingOpeningStockRequest.FinishedGoodLine line : finishedGoods) {
            FinishedGood finishedGood = requireFinishedGood(company, line.productCode());
            String batchCode = resolveOpeningBatchCode(referenceNumber, finishedGood.getProductCode(), line.batchCode());
            FinishedGoodBatch batch = finishedGoodBatchRepository.findByFinishedGoodAndBatchCode(finishedGood, batchCode)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Opening stock reference already posted; missing batch " + batchCode));
            BigDecimal quantity = requirePositive(line.quantity(), "quantity");
            BigDecimal unitCost = requirePositive(line.unitCost(), "unitCost");
            if (batch.getQuantityTotal().compareTo(quantity) != 0 || batch.getUnitCost().compareTo(unitCost) != 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Opening stock idempotency conflict for finished good " + finishedGood.getProductCode()
                                + " batch " + batchCode);
            }
            skipped++;
        }
        return skipped;
    }

    private void ensureNoOpeningBatchConflicts(List<OnboardingOpeningStockRequest.RawMaterialLine> rawMaterials,
                                               List<OnboardingOpeningStockRequest.FinishedGoodLine> finishedGoods,
                                               Company company,
                                               String referenceNumber) {
        for (OnboardingOpeningStockRequest.RawMaterialLine line : rawMaterials) {
            RawMaterial material = requireRawMaterial(company, line.sku());
            String batchCode = resolveOpeningBatchCode(referenceNumber, material.getSku(), line.batchCode());
            if (rawMaterialBatchRepository.findByRawMaterialAndBatchCode(material, batchCode).isPresent()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Opening stock batch already exists: " + batchCode);
            }
        }
        for (OnboardingOpeningStockRequest.FinishedGoodLine line : finishedGoods) {
            FinishedGood finishedGood = requireFinishedGood(company, line.productCode());
            String batchCode = resolveOpeningBatchCode(referenceNumber, finishedGood.getProductCode(), line.batchCode());
            if (finishedGoodBatchRepository.findByFinishedGoodAndBatchCode(finishedGood, batchCode).isPresent()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Opening stock batch already exists: " + batchCode);
            }
        }
    }

    private String resolveOpeningBatchCode(String referenceNumber, String itemCode, String provided) {
        if (StringUtils.hasText(provided)) {
            return provided.trim();
        }
        String base = "OPEN-" + referenceNumber + "-" + itemCode;
        String cleaned = base.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "-");
        cleaned = cleaned.replaceAll("-{2,}", "-");
        if (cleaned.length() > 48) {
            cleaned = cleaned.substring(0, 48);
        }
        return cleaned;
    }

    private RawMaterial requireRawMaterial(Company company, String sku) {
        if (!StringUtils.hasText(sku)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Raw material SKU is required");
        }
        return rawMaterialRepository.findByCompanyAndSku(company, sku.trim())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Raw material not found for SKU " + sku));
    }

    private FinishedGood requireFinishedGood(Company company, String productCode) {
        if (!StringUtils.hasText(productCode)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Finished good code is required");
        }
        return finishedGoodRepository.findByCompanyAndProductCode(company, productCode.trim())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Finished good not found for code " + productCode));
    }

    private Long resolveInventoryAccount(Company company, Long inventoryAccountId) {
        if (inventoryAccountId != null) {
            return requireActiveAccount(company, inventoryAccountId, "inventoryAccountId").getId();
        }
        Long defaultInventory = company.getDefaultInventoryAccountId();
        if (defaultInventory == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Company default inventory account must be configured");
        }
        return requireActiveAccount(company, defaultInventory, "defaultInventoryAccountId").getId();
    }

    private Long requireAccount(Long accountId, Company company) {
        return requireActiveAccount(company, accountId, "offsetAccountId").getId();
    }

    private Dealer resolveDealer(Company company, Long partnerId, String partnerCode) {
        if (partnerId != null) {
            return companyEntityLookup.requireDealer(company, partnerId);
        }
        if (StringUtils.hasText(partnerCode)) {
            return dealerRepository.findByCompanyAndCodeIgnoreCase(company, partnerCode.trim())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Dealer not found for code " + partnerCode));
        }
        throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Dealer is required");
    }

    private Supplier resolveSupplier(Company company, Long partnerId, String partnerCode) {
        if (partnerId != null) {
            return companyEntityLookup.requireSupplier(company, partnerId);
        }
        if (StringUtils.hasText(partnerCode)) {
            return supplierRepository.findByCompanyAndCodeIgnoreCase(company, partnerCode.trim())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Supplier not found for code " + partnerCode));
        }
        throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Supplier is required");
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, field + " must be positive");
        }
        return value;
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, field + " is required");
        }
        return value.trim();
    }

    private String resolveMemo(String baseMemo, String fallback, String lineMemo) {
        if (StringUtils.hasText(lineMemo)) {
            return lineMemo.trim();
        }
        if (StringUtils.hasText(baseMemo)) {
            return baseMemo.trim();
        }
        return fallback;
    }

    private LocalDate resolveEntryDate(Company company, LocalDate entryDate) {
        if (entryDate != null) {
            return entryDate;
        }
        return companyClock.today(company);
    }

    private Instant resolveManufacturedAt(Company company, LocalDate manufacturedDate) {
        if (manufacturedDate == null) {
            return Instant.now();
        }
        ZoneId zone = ZoneId.of(company.getTimezone() == null ? "UTC" : company.getTimezone());
        return manufacturedDate.atStartOfDay(zone).toInstant();
    }

    private String resolveUnit(String requested, String fallback) {
        if (StringUtils.hasText(requested)) {
            return requested.trim();
        }
        return StringUtils.hasText(fallback) ? fallback.trim() : "UNIT";
    }

    private BigDecimal safeNumber(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private ProductionBrandDto toBrandDto(ProductionBrand brand) {
        long productCount = productRepository.findByBrandOrderByProductNameAsc(brand).size();
        return new ProductionBrandDto(brand.getId(), brand.getPublicId(), brand.getName(), brand.getCode(), productCount);
    }

    private ProductionCategoryDto toCategoryDto(ProductionCategory category) {
        return new ProductionCategoryDto(category.getId(), category.getPublicId(),
                category.getCode(), category.getName(), category.getDescription());
    }

    private ProductionProductDto toProductDto(ProductionProduct product) {
        return new ProductionProductDto(
                product.getId(),
                product.getPublicId(),
                product.getBrand().getId(),
                product.getBrand().getName(),
                product.getBrand().getCode(),
                product.getProductName(),
                product.getCategory(),
                product.getDefaultColour(),
                product.getSizeLabel(),
                product.getUnitOfMeasure(),
                product.getSkuCode(),
                product.isActive(),
                product.getBasePrice(),
                product.getGstRate(),
                product.getMinDiscountPercent(),
                product.getMinSellingPrice(),
                product.getMetadata()
        );
    }

    private String normalizeSku(String sku) {
        if (!StringUtils.hasText(sku)) {
            return null;
        }
        String upper = sku.trim().toUpperCase(Locale.ROOT);
        upper = NON_SKU_CHAR.matcher(upper).replaceAll("");
        upper = upper.replaceAll("-{2,}", "-");
        return upper.isBlank() ? null : upper;
    }

    private String sanitizeCode(String code) {
        String sanitized = sanitizeSegment(code);
        if (sanitized.length() > 12) {
            sanitized = sanitized.substring(0, 12);
        }
        return sanitized.isBlank() ? "BRAND" : sanitized;
    }

    private String normalizeCategoryCode(String code) {
        String normalized = StringUtils.hasText(code) ? code.trim().toUpperCase(Locale.ROOT) : "GENERAL";
        normalized = normalized.replace(' ', '_');
        return normalized.length() > 24 ? normalized.substring(0, 24) : normalized;
    }

    private String sanitizeSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return NON_ALPHANUM.matcher(upper).replaceAll("");
    }

    private boolean isRawMaterialCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return false;
        }
        String normalized = category.replace('-', '_').toUpperCase(Locale.ROOT);
        return RAW_MATERIAL_CATEGORIES.stream().anyMatch(normalized::equalsIgnoreCase);
    }

    private void requireProductDefaultsAndMetadata(Company company, String category, Map<String, Object> metadata) {
        if (isRawMaterialCategory(category)) {
            return;
        }
        try {
            companyDefaultAccountsService.requireDefaults();
        } catch (IllegalStateException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, ex.getMessage());
        }
        requireMetadataAccount(company, metadata, "wipAccountId", true);
        requireMetadataAccount(company, metadata, "semiFinishedAccountId", true);
    }

    private boolean hasLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue() > 0;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim()) > 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private Long requireMetadataAccount(Company company, Map<String, Object> metadata, String key, boolean required) {
        Long value = parseLong(metadata == null ? null : metadata.get(key));
        if (value == null || value <= 0) {
            if (required) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, key + " is required in product metadata");
            }
            return null;
        }
        requireActiveAccount(company, value, key);
        return value;
    }

    private Account requireActiveAccount(Company company, Long accountId, String fieldName) {
        if (accountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, fieldName + " is required");
        }
        Account account;
        try {
            account = companyEntityLookup.requireAccount(company, accountId);
        } catch (IllegalArgumentException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    fieldName + " must reference an account in this company");
        }
        if (!account.isActive()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    fieldName + " must reference an active account");
        }
        return account;
    }

    private List<AccountDto> candidatesByType(List<Account> accounts, AccountType type) {
        return accounts.stream()
                .filter(account -> type.equals(account.getType()))
                .limit(ACCOUNT_SUGGESTION_LIMIT)
                .map(this::toAccountDto)
                .toList();
    }

    private List<AccountDto> candidatesByTypes(List<Account> accounts, List<AccountType> types) {
        return accounts.stream()
                .filter(account -> types.contains(account.getType()))
                .limit(ACCOUNT_SUGGESTION_LIMIT)
                .map(this::toAccountDto)
                .toList();
    }

    private List<AccountDto> candidatesByTypeWithHint(List<Account> accounts, AccountType type, String hint) {
        String token = hint == null ? "" : hint.trim().toUpperCase(Locale.ROOT);
        return accounts.stream()
                .filter(account -> type.equals(account.getType()))
                .filter(account -> token.isEmpty() || containsHint(account, token))
                .limit(ACCOUNT_SUGGESTION_LIMIT)
                .map(this::toAccountDto)
                .toList();
    }

    private boolean containsHint(Account account, String token) {
        String code = account.getCode() == null ? "" : account.getCode().toUpperCase(Locale.ROOT);
        String name = account.getName() == null ? "" : account.getName().toUpperCase(Locale.ROOT);
        return code.contains(token) || name.contains(token);
    }

    private AccountDto toAccountDto(Account account) {
        return new AccountDto(account.getId(), account.getPublicId(),
                account.getCode(), account.getName(), account.getType(), account.getBalance());
    }

    private <T> String nextCode(Company company,
                                String base,
                                int maxLength,
                                org.springframework.data.jpa.repository.JpaRepository<T, Long> repository) {
        String candidate = base;
        int counter = 1;
        while (existsCode(company, candidate, repository)) {
            String suffix = String.valueOf(counter++);
            int maxPrefix = Math.max(4, maxLength - suffix.length());
            String prefix = base.length() > maxPrefix ? base.substring(0, maxPrefix) : base;
            candidate = prefix + suffix;
        }
        return candidate;
    }

    private <T> boolean existsCode(Company company, String code, org.springframework.data.jpa.repository.JpaRepository<T, Long> repository) {
        if (repository instanceof ProductionBrandRepository brandRepo) {
            return brandRepo.findByCompanyAndCodeIgnoreCase(company, code).isPresent();
        }
        if (repository instanceof ProductionCategoryRepository categoryRepo) {
            return categoryRepo.findByCompanyAndCodeIgnoreCase(company, code).isPresent();
        }
        return false;
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
