package com.urbantransport.customer.service.impl;

import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.dto.CreateCustomerRequest;
import com.urbantransport.customer.dto.CustomerResponse;
import com.urbantransport.customer.dto.CustomerSummaryResponse;
import com.urbantransport.customer.dto.UpdateProfileRequest;
import com.urbantransport.customer.event.CustomerNitChangedPayload;
import com.urbantransport.customer.event.EventTypes;
import com.urbantransport.customer.repository.CustomerRepository;
import com.urbantransport.customer.service.OutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static com.urbantransport.customer.support.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    private static final String DPI = "1234567890123";

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OutboxService outboxService;
    @InjectMocks
    private CustomerServiceImpl service;

    private final UUID id = UUID.randomUUID();

    // ------------------------------------------------------------------ getProfile

    @Test
    @DisplayName("getProfile devuelve el perfil; si no existe -> 404")
    void getProfile() {
        when(customerRepository.findById(id)).thenReturn(Optional.of(customer()));

        CustomerResponse response = service.getProfile(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getNit()).isEqualTo("12345678");

        UUID missing = UUID.randomUUID();
        when(customerRepository.findById(missing)).thenReturn(Optional.empty());
        assertApiError(() -> service.getProfile(missing), HttpStatus.NOT_FOUND, "Cliente no encontrado");
    }

    // ------------------------------------------------------------------ updateProfile

    @Test
    @DisplayName("mismo NIT (aunque venga con guion): actualiza los datos, recorta textos y NO emite evento")
    void updateWithoutNitChange() {
        CustomerEntity existing = customer();
        when(customerRepository.findById(id)).thenReturn(Optional.of(existing));

        CustomerResponse response = service.updateProfile(id, update("1234567-8", DPI));

        assertThat(response.getFullName()).isEqualTo("Ana Pérez");
        assertThat(existing.getAddress()).isEqualTo("Zona 3");
        assertThat(existing.getPhone()).isEqualTo("55598765");
        verify(customerRepository, never()).existsByNit(anyString());
        verify(customerRepository, never()).existsByDpi(anyString());
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("NIT nuevo: se guarda normalizado y se emite CustomerNitChanged")
    void updateWithNitChange() {
        CustomerEntity existing = customer();
        when(customerRepository.findById(id)).thenReturn(Optional.of(existing));
        when(customerRepository.existsByNit("87654321")).thenReturn(false);

        CustomerResponse response = service.updateProfile(id, update("8765432-1", DPI));

        assertThat(response.getNit()).isEqualTo("87654321");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).record(eq(EventTypes.AGGREGATE_CUSTOMER), eq(id),
                eq(EventTypes.CUSTOMER_NIT_CHANGED), payload.capture());
        CustomerNitChangedPayload event = (CustomerNitChangedPayload) payload.getValue();
        assertThat(event.getCustomerId()).isEqualTo(id);
        assertThat(event.getNit()).isEqualTo("87654321");
    }

    @Test
    @DisplayName("NIT que ya es de otro cliente -> 409 y sin evento")
    void updateWithTakenNit() {
        when(customerRepository.findById(id)).thenReturn(Optional.of(customer()));
        when(customerRepository.existsByNit("87654321")).thenReturn(true);

        assertApiError(() -> service.updateProfile(id, update("87654321", DPI)), HttpStatus.CONFLICT,
                "Ese NIT ya pertenece a otro cliente");
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("NIT con formato inválido -> 400")
    void updateWithInvalidNit() {
        when(customerRepository.findById(id)).thenReturn(Optional.of(customer()));

        assertApiError(() -> service.updateProfile(id, update("ABC", DPI)), HttpStatus.BAD_REQUEST,
                "El NIT no tiene un formato válido");
    }

    @Test
    @DisplayName("DPI nuevo: se verifica que no sea de otro cliente; si es libre se guarda")
    void updateWithNewDpi() {
        CustomerEntity existing = customer();
        when(customerRepository.findById(id)).thenReturn(Optional.of(existing));
        when(customerRepository.existsByDpi("9999999999999")).thenReturn(false);

        service.updateProfile(id, update("12345678", "9999999999999"));

        assertThat(existing.getDpi()).isEqualTo("9999999999999");
    }

    @Test
    @DisplayName("DPI de otro cliente -> 409")
    void updateWithTakenDpi() {
        when(customerRepository.findById(id)).thenReturn(Optional.of(customer()));
        when(customerRepository.existsByDpi("9999999999999")).thenReturn(true);

        assertApiError(() -> service.updateProfile(id, update("12345678", "9999999999999")),
                HttpStatus.CONFLICT, "Ese DPI ya pertenece a otro cliente");
    }

    @Test
    @DisplayName("cliente inexistente -> 404")
    void updateMissingCustomer() {
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertApiError(() -> service.updateProfile(id, update("12345678", DPI)), HttpStatus.NOT_FOUND,
                "Cliente no encontrado");
    }

    // ------------------------------------------------------------------ findSummaryByNit

    @Test
    @DisplayName("buscar por NIT normaliza la entrada; si no existe -> 404")
    void findSummaryByNit() {
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(customer()));

        CustomerSummaryResponse summary = service.findSummaryByNit("1234567-8");

        assertThat(summary.getNit()).isEqualTo("12345678");
        assertThat(summary.isHasAccount()).isFalse();

        when(customerRepository.findByNit("99")).thenReturn(Optional.empty());
        assertApiError(() -> service.findSummaryByNit("99"), HttpStatus.NOT_FOUND,
                "No existe un cliente con ese NIT");
    }

    // ------------------------------------------------------------------ createWithoutAccount

    @Test
    @DisplayName("alta en ventanilla: guarda el cliente sin cuenta con el NIT normalizado y los textos recortados")
    void createWithoutAccount() {
        when(customerRepository.existsByNit("12345678")).thenReturn(false);
        when(customerRepository.existsByDpi(DPI)).thenReturn(false);
        when(customerRepository.save(any(CustomerEntity.class))).thenAnswer(call -> call.getArgument(0));

        CustomerSummaryResponse response = service.createWithoutAccount(
                new CreateCustomerRequest("1234567-8", "  Ana Pérez ", DPI, "55512345", "  Zona 1 "));

        ArgumentCaptor<CustomerEntity> saved = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(customerRepository).save(saved.capture());
        assertThat(saved.getValue().getNit()).isEqualTo("12345678");
        assertThat(saved.getValue().getFullName()).isEqualTo("Ana Pérez");
        assertThat(saved.getValue().getAddress()).isEqualTo("Zona 1");
        assertThat(saved.getValue().getUserId()).isNull();
        assertThat(response.isHasAccount()).isFalse();
    }

    @Test
    @DisplayName("DPI y dirección son opcionales: sin DPI no se consulta el repositorio por DPI")
    void createWithoutOptionalFields() {
        when(customerRepository.existsByNit("12345678")).thenReturn(false);
        when(customerRepository.save(any(CustomerEntity.class))).thenAnswer(call -> call.getArgument(0));

        service.createWithoutAccount(new CreateCustomerRequest("12345678", "Ana", null, null, null));

        verify(customerRepository, never()).existsByDpi(anyString());
        ArgumentCaptor<CustomerEntity> saved = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(customerRepository).save(saved.capture());
        assertThat(saved.getValue().getAddress()).isNull();
        assertThat(saved.getValue().getDpi()).isNull();
    }

    @Test
    @DisplayName("NIT repetido -> 409; NIT inválido -> 400; DPI repetido -> 409; ninguno guarda")
    void createWithoutAccountRejections() {
        when(customerRepository.existsByNit("12345678")).thenReturn(true);
        assertApiError(() -> service.createWithoutAccount(new CreateCustomerRequest("12345678", "Ana", null, null, null)),
                HttpStatus.CONFLICT, "Ya existe un cliente con ese NIT");

        when(customerRepository.existsByNit("ABC")).thenReturn(false);
        assertApiError(() -> service.createWithoutAccount(new CreateCustomerRequest("abc", "Ana", null, null, null)),
                HttpStatus.BAD_REQUEST, "El NIT no tiene un formato válido");

        when(customerRepository.existsByNit("87654321")).thenReturn(false);
        when(customerRepository.existsByDpi(DPI)).thenReturn(true);
        assertApiError(() -> service.createWithoutAccount(new CreateCustomerRequest("87654321", "Ana", DPI, null, null)),
                HttpStatus.CONFLICT, "Ese DPI ya está registrado con otro NIT");

        verify(customerRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ helpers

    private CustomerEntity customer() {
        return CustomerEntity.builder().id(id).nit("12345678").dpi(DPI).fullName("Ana").phone("55512345")
                .address("Zona 1").build();
    }

    private static UpdateProfileRequest update(String nit, String dpi) {
        return new UpdateProfileRequest("  Ana Pérez  ", nit, dpi, "55598765", "  Zona 3  ");
    }
}