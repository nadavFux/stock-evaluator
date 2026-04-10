import React, { useEffect, useRef } from 'react';
import { createChart, ColorType, CandlestickSeries, LineSeries, createSeriesMarkers } from 'lightweight-charts';
import type { IChartApi } from 'lightweight-charts';

interface StockChartProps {
    data: any; // StockGraphState
    markers?: any[]; // TradePoint[]
}

const StockChart: React.FC<StockChartProps> = ({ data, markers }) => {
    const chartContainerRef = useRef<HTMLDivElement>(null);
    const chartRef = useRef<IChartApi | null>(null);

    useEffect(() => {
        if (!chartContainerRef.current) return;

        const chart = createChart(chartContainerRef.current, {
            layout: {
                background: { type: ColorType.Solid, color: '#1e293b' },
                textColor: '#94a3b8',
            },
            grid: {
                vertLines: { color: '#334155' },
                horzLines: { color: '#334155' },
            },
            width: chartContainerRef.current.clientWidth,
            height: 400,
        });

        const candlestickSeries = chart.addSeries(CandlestickSeries, {
            upColor: '#10b981',
            downColor: '#ef4444',
            borderVisible: false,
            wickUpColor: '#10b981',
            wickDownColor: '#ef4444',
        });

        const maSeries = chart.addSeries(LineSeries, {
            color: '#3b82f6',
            lineWidth: 2,
            title: 'Long MA',
        });

        // Prepare data
        if (!data?.closePrices || !data?.dates || !data?.avgs) {
            console.error('Incomplete data for chart', data);
            return;
        }

        const chartData = data.closePrices.map((price: number, i: number) => {
            if (data.dates[i] === undefined) return null;
            return {
                time: data.dates[i],
                open: price * 0.99,
                high: price * 1.01,
                low: price * 0.98,
                close: price,
            };
        }).filter(Boolean);

        const maData = data.avgs.map((avg: number, i: number) => {
            if (data.dates[i] === undefined || avg === null || avg === undefined) return null;
            return {
                time: data.dates[i],
                value: avg,
            };
        }).filter(Boolean);

        if (chartData.length === 0) return;

        candlestickSeries.setData(chartData as any);
        maSeries.setData(maData as any);

        // Add Markers
        if (markers && markers.length > 0) {
            const chartMarkers = markers.map(m => ({
                time: m.date,
                position: m.type === 'BUY' ? 'belowBar' : 'aboveBar',
                color: m.type === 'BUY' ? '#10b981' : '#ef4444',
                shape: m.type === 'BUY' ? 'arrowUp' : 'arrowDown',
                text: m.type
            }));
            createSeriesMarkers(candlestickSeries, chartMarkers as any);
        }

        chart.timeScale().fitContent();
        chartRef.current = chart;

        const handleResize = () => {
            if (chartContainerRef.current && chartRef.current) {
                chartRef.current.applyOptions({ width: chartContainerRef.current.clientWidth });
            }
        };

        window.addEventListener('resize', handleResize);

        return () => {
            window.removeEventListener('resize', handleResize);
            chart.remove();
        };
    }, [data]);

    return <div ref={chartContainerRef} className="w-full h-full rounded-xl overflow-hidden border border-slate-700" />;
};

export default StockChart;
