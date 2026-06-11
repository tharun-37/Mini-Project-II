document.addEventListener('DOMContentLoaded', () => {
    fetchReports();
    setInterval(fetchReports, 5000); // Auto-refresh every 5s

    // Modal close event
    document.querySelector('.close-btn').addEventListener('click', () => {
        document.getElementById('image-modal').classList.remove('active');
    });
});

let allReports = [];

async function fetchReports() {
    try {
        const response = await fetch('/api/reports');
        const reports = await response.json();
        allReports = reports;
        updateUI(reports);
    } catch (error) {
        console.error("Error fetching reports:", error);
    }
}

function updateUI(reports) {
    // Update Metrics
    document.getElementById('total-reports').textContent = reports.length;
    document.getElementById('pending-reports').textContent = reports.filter(r => r.status === 'Pending').length;
    document.getElementById('processed-reports').textContent = reports.filter(r => r.status === 'Processed').length;

    // Update Table
    const tbody = document.getElementById('reports-body');
    tbody.innerHTML = '';

    reports.forEach(report => {
        const tr = document.createElement('tr');
        
        const statusClass = report.status.toLowerCase() === 'pending' ? 'pending' : 'processed';
        const actionButton = report.status === 'Pending' 
            ? `<button class="btn btn-action" onclick="markProcessed(${report.id})">Mark Processed</button>`
            : '';

        tr.innerHTML = `
            <td>#${report.id}</td>
            <td>${report.timestamp}</td>
            <td><strong>${report.plate_number}</strong></td>
            <td>${report.violation_type}</td>
            <td><span class="badge ${statusClass}">${report.status}</span></td>
            <td class="actions-cell">
                <button class="btn btn-view" onclick="viewReport(${report.id})">View Image</button>
                <button class="btn btn-view" style="background-color: #25D366; border: 1px solid #128C7E;" onclick="sendWhatsAppWarning(${report.id})">Send Warning</button>
                ${actionButton}
            </td>
        `;
        tbody.appendChild(tr);
    });
}

let currentImageIndex = 0;
let currentImagesList = [];

function viewReport(id) {
    const report = allReports.find(r => r.id === id);
    if (report) {
        currentImagesList = report.image_filename.split(',');
        currentImageIndex = 0;
        
        updateCarousel();
        
        document.getElementById('modal-plate').textContent = report.plate_number;
        document.getElementById('modal-violation').textContent = report.violation_type;
        document.getElementById('image-modal').classList.add('active');
    }
}

function updateCarousel() {
    const imgEl = document.getElementById('modal-image');
    imgEl.src = `/uploads/${currentImagesList[currentImageIndex]}`;
    
    const dotsContainer = document.getElementById('carousel-dots');
    dotsContainer.innerHTML = '';
    
    if (currentImagesList.length <= 1) {
        document.querySelector('.prev-btn').style.display = 'none';
        document.querySelector('.next-btn').style.display = 'none';
        return;
    }
    
    document.querySelector('.prev-btn').style.display = 'block';
    document.querySelector('.next-btn').style.display = 'block';
    
    currentImagesList.forEach((_, idx) => {
        const dot = document.createElement('span');
        dot.className = `dot ${idx === currentImageIndex ? 'active' : ''}`;
        dot.style.cssText = `
            width: 8px;
            height: 8px;
            background: ${idx === currentImageIndex ? '#10b981' : '#475569'};
            border-radius: 50%;
            display: inline-block;
            cursor: pointer;
        `;
        dot.onclick = () => {
            currentImageIndex = idx;
            updateCarousel();
        };
        dotsContainer.appendChild(dot);
    });
}

function prevSlide() {
    if (currentImagesList.length === 0) return;
    currentImageIndex = (currentImageIndex - 1 + currentImagesList.length) % currentImagesList.length;
    updateCarousel();
}

function nextSlide() {
    if (currentImagesList.length === 0) return;
    currentImageIndex = (currentImageIndex + 1) % currentImagesList.length;
    updateCarousel();
}

async function markProcessed(id) {
    try {
        const response = await fetch(`/api/reports/${id}/status`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ status: 'Processed' })
        });
        
        if (response.ok) {
            fetchReports(); // Refresh
        }
    } catch (error) {
        console.error("Error updating status:", error);
    }
}

async function sendWhatsAppWarning(id) {
    try {
        const response = await fetch(`/api/reports/${id}/whatsapp`, {
            method: 'POST'
        });
        const result = await response.json();
        
        if (response.ok) {
            alert('WhatsApp message sent successfully!');
        } else {
            alert('Failed to send WhatsApp message: ' + (result.error || 'Unknown error'));
            console.error('Meta Error:', result.meta_error);
        }
    } catch (error) {
        console.error("Error sending WhatsApp:", error);
        alert('Failed to connect to server to send WhatsApp message.');
    }
}
