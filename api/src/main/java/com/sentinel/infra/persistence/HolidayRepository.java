package com.sentinel.infra.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

	Optional<Holiday> findByIdAndArchivedAtIsNull(Long id);

	/** 날짜 순 (양 끝 포함) */
	List<Holiday> findByArchivedAtIsNullAndDateBetweenOrderByDate(LocalDate from, LocalDate to);

	boolean existsByDateAndArchivedAtIsNull(LocalDate date);

	List<Holiday> findByArchivedAtIsNullAndDateIn(Collection<LocalDate> dates);

}
