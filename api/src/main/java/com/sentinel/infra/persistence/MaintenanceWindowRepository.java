package com.sentinel.infra.persistence;

import java.util.Optional;

import com.sentinel.domain.MaintenanceWindow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, Long>, MaintenanceWindowQueries {

	Optional<MaintenanceWindow> findByIdAndArchivedAtIsNull(Long id);

}
