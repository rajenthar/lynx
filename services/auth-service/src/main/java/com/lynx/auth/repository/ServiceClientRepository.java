package com.lynx.auth.repository;

import com.lynx.auth.domain.ServiceClient;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceClientRepository extends JpaRepository<ServiceClient, String> {

  Optional<ServiceClient> findByClientId(String clientId);
}
